package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * Talks to the backend chat the same way the Android app does: STOMP 1.2 over the /ws WebSocket.
 * Each call opens a short connection, does one job, and closes it.
 */
@Singleton
public class ChatRelay {

    public enum Channel {
        RENTAL("rental-messages", "/user/queue/rental-chat.messages",
                "/app/rental-chat.send", "/app/rental-chat.delivered"),
        ROOMMATE("roommate-messages", "/user/queue/chat.messages",
                "/app/chat.send", "/app/chat.delivered");

        private final String subscriptionId;
        private final String inbox;
        private final String sendTo;
        private final String deliveredTo;

        Channel(String subscriptionId, String inbox, String sendTo, String deliveredTo) {
            this.subscriptionId = subscriptionId;
            this.inbox = inbox;
            this.sendTo = sendTo;
            this.deliveredTo = deliveredTo;
        }
    }

    /** reason is one of: auth, rejected, timeout, unavailable. message is the saved message after a send. */
    public record Outcome(boolean ok, String reason, JsonNode message) {}

    private static final int MAX_FRAME_BYTES = 8192; // the same limit as the Android app
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private final String wsUrl;
    private final String host;

    @Inject
    public ChatRelay(Config config) {
        String base = config.getString("makao.api.baseUrl");
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.wsUrl = base.replaceFirst("^http", "ws") + "/ws";   // https -> wss, http -> ws
        this.host = URI.create(base).getHost();
    }

    // ---------- Public calls ----------

    /** Sends one message and waits for the server's saved copy. */
    public CompletionStage<Outcome> send(String access, Channel channel, String threadId,
                                         String clientMessageId, String content) {
        if (!safe(access)) return CompletableFuture.completedFuture(bad("auth"));

        ObjectNode body = MAPPER.createObjectNode();
        body.put("threadId", threadId);
        body.put("clientMessageId", clientMessageId);
        body.put("content", content);

        String frame = sendFrame(channel.sendTo, body.toString());
        if (frame.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
            return CompletableFuture.completedFuture(bad("rejected"));
        }
        return run(access, channel, frame, clientMessageId);
    }

    /** Tells the backend that these incoming messages arrived. Nothing comes back. */
    public CompletionStage<Outcome> delivered(String access, Channel channel, String threadId, List<String> ids) {
        if (!safe(access) || ids.isEmpty()) return CompletableFuture.completedFuture(bad("auth"));

        ObjectNode body = MAPPER.createObjectNode();
        body.put("threadId", threadId);
        ArrayNode list = body.putArray("messageIds");
        for (String id : ids) {
            list.add(id);
        }

        String frame = sendFrame(channel.deliveredTo, body.toString());
        if (frame.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
            return CompletableFuture.completedFuture(bad("rejected"));
        }
        return run(access, channel, frame, null);
    }

    // ---------- One connection ----------

    private CompletableFuture<Outcome> run(String access, Channel channel, String frame, String awaitedId) {
        Session session = new Session(access, channel, frame, awaitedId);

        CompletableFuture<WebSocket> opening = client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .header("ngrok-skip-browser-warning", "true")
                .buildAsync(URI.create(wsUrl), session);

        opening.whenComplete((socket, error) -> {
            if (error != null) session.result.complete(bad("unavailable"));
        });

        return session.result
                .orTimeout(12, TimeUnit.SECONDS)
                .exceptionally(error -> bad("timeout"))
                .whenComplete((outcome, error) -> opening.thenAccept(WebSocket::abort));
    }

    private final class Session implements WebSocket.Listener {

        private final String access;
        private final Channel channel;
        private final String outgoing;
        private final String awaitedId;      // null when we only send delivery receipts
        private final CompletableFuture<Outcome> result = new CompletableFuture<>();
        private final StringBuilder buffer = new StringBuilder();
        private boolean connected;

        Session(String access, Channel channel, String outgoing, String awaitedId) {
            this.access = access;
            this.channel = channel;
            this.outgoing = outgoing;
            this.awaitedId = awaitedId;
        }

        @Override
        public void onOpen(WebSocket ws) {
            // The token is sent only here, in the CONNECT frame, as the Android app does.
            ws.sendText("CONNECT\naccept-version:1.2\nhost:" + host
                    + "\nAuthorization:Bearer " + access + "\nheart-beat:0,0\n\n\u0000", true);
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buffer.append(data);
            if (buffer.length() > 131072) {
                result.complete(bad("rejected"));
                return null;
            }
            if (last) {
                String raw = buffer.toString();
                buffer.setLength(0);
                handle(ws, raw);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            result.complete(bad("unavailable"));
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            result.complete(bad("unavailable"));
        }

        private void handle(WebSocket ws, String raw) {
            String text = raw.replace("\r\n", "\n");
            int start = 0;
            while (start < text.length() && text.charAt(start) == '\n') start++;
            if (start == text.length()) return;                       // a heartbeat
            text = text.substring(start);

            int split = text.indexOf("\n\n");
            String head = split < 0 ? text : text.substring(0, split);
            String body = split < 0 ? "" : text.substring(split + 2);
            int nul = body.indexOf('\0');
            if (nul >= 0) body = body.substring(0, nul);

            String[] lines = head.split("\n");
            String command = lines[0].trim();
            String subscription = header(lines, "subscription");

            switch (command) {
                case "CONNECTED": {
                    connected = true;
                    List<String> frames = new ArrayList<>();
                    if (awaitedId != null) {
                        frames.add(subscribe(channel.subscriptionId, channel.inbox));
                        frames.add(subscribe("chat-errors", "/user/queue/chat.errors"));
                    }
                    frames.add(outgoing);
                    sendAll(ws, frames);
                    break;
                }

                case "MESSAGE": {
                    if (awaitedId == null) break;
                    if (channel.subscriptionId.equals(subscription)) {
                        try {
                            JsonNode message = MAPPER.readTree(body);
                            if (awaitedId.equals(message.path("clientMessageId").asText(""))) {
                                result.complete(new Outcome(true, null, message));
                            }
                        } catch (Exception ignored) {
                            // not a message we can read; keep waiting for ours
                        }
                    } else if ("chat-errors".equals(subscription)) {
                        result.complete(bad("rejected"));
                    }
                    break;
                }

                case "ERROR":
                    result.complete(bad(connected ? "rejected" : "auth"));
                    break;

                default:
                    break;
            }
        }

        private void sendAll(WebSocket ws, List<String> frames) {
            CompletableFuture<WebSocket> chain = CompletableFuture.completedFuture(ws);
            for (String frame : frames) {
                chain = chain.thenCompose(w -> w.sendText(frame, true));
            }
            chain.whenComplete((w, error) -> {
                if (error != null) {
                    result.complete(bad("unavailable"));
                } else if (awaitedId == null) {
                    // Delivery receipts get no answer. Give the frame a moment to arrive, then finish.
                    CompletableFuture.delayedExecutor(700, TimeUnit.MILLISECONDS)
                            .execute(() -> result.complete(new Outcome(true, null, null)));
                }
            });
        }
    }

    // ---------- Frames ----------

    private static String sendFrame(String destination, String json) {
        int length = json.getBytes(StandardCharsets.UTF_8).length;
        return "SEND\ndestination:" + destination
                + "\ncontent-type:application/json\ncontent-length:" + length
                + "\n\n" + json + "\u0000";
    }

    private static String subscribe(String id, String destination) {
        return "SUBSCRIBE\nid:" + id + "\ndestination:" + destination + "\nack:auto\n\n\u0000";
    }

    private static String header(String[] lines, String name) {
        String prefix = name + ":";
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith(prefix)) return lines[i].substring(prefix.length());
        }
        return "";
    }

    /** A token must never contain a line break, or it could inject extra STOMP headers. */
    private static boolean safe(String access) {
        return access != null && !access.isEmpty() && access.indexOf('\n') < 0 && access.indexOf('\r') < 0;
    }

    private static Outcome bad(String reason) {
        return new Outcome(false, reason, null);
    }
}