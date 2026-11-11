package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class MessagesController extends Controller {

    private static final Set<String> ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");

    /** The name of the paging parameter the backend expects. Change this one word if it differs. */
    private static final String CURSOR_PARAM = "cursor";

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
            .withZone(ZoneId.of("Africa/Nairobi"));

    /** One conversation in the inbox. An empty href means it can't be opened. */
    public record Chat(String title, String preview, long unread, String kind, String href) {}

    public record MessagesView(List<Chat> chats, boolean loadFailed) {}

    /** One message inside a conversation. */
    public record Msg(String id, String text, String time, boolean mine, boolean delivered, boolean masked) {}

    public record ThreadView(
            String title,
            String kind,
            String threadId,
            String channel,
            boolean open,
            String olderUrl,
            boolean hasOlder,
            String nextCursor,
            List<Msg> messages,
            boolean loadFailed) {}

    private final AuthedApi api;

    @Inject
    public MessagesController(AuthedApi api) {
        this.api = api;
    }

    // ---------- Inbox ----------

    public CompletionStage<Result> index(Http.Request request) {
        String role = roleOf(request);
        if (role.isEmpty()) {
            return CompletableFuture.completedFuture(redirect("/get-started?mode=login"));
        }

        List<String> paths = pathsFor(role);

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) {
                return redirect("/get-started?mode=login").withNewSession();
            }

            List<Chat> chats = new ArrayList<>();
            boolean anyLoaded = false;

            for (int i = 0; i < paths.size(); i++) {
                JsonNode json = batch.json.get(i);
                if (json == null) continue;
                anyLoaded = true;

                boolean roommate = paths.get(i).contains("roommate-chat");
                String slug = roommate ? "roommate" : "home";
                String label = roommate ? "Roommate chat" : "Home enquiry";

                for (JsonNode n : listOf(json)) {
                    String id = idOf(n);
                    String href = isSafeId(id) ? "/dashboard/messages/" + slug + "/" + id : "";
                    chats.add(new Chat(title(n), preview(n), n.path("unreadCount").asLong(0), label, href));
                }
            }

            // Conversations with unread messages first; the rest keep the backend's order.
            chats.sort(Comparator.comparingLong(Chat::unread).reversed());

            return withFreshSession(request, batch,
                    ok(views.html.messages.render(role, new MessagesView(chats, !anyLoaded), request)));
        });
    }

    // ---------- One conversation ----------

    public CompletionStage<Result> thread(Http.Request request, String kind, String id) {
        String role = roleOf(request);
        if (role.isEmpty()) {
            return CompletableFuture.completedFuture(redirect("/get-started?mode=login"));
        }
        // The id goes into a backend address, so only plain ids are accepted.
        if (!isSafeId(id) || !allowed(role, kind)) {
            return CompletableFuture.completedFuture(redirect("/dashboard/messages"));
        }

        boolean roommate = kind.equals("roommate");
        String base = threadsBase(role, roommate);

        List<String> paths = List.of("/api/v1/profile/me", base + "/" + id + "/messages", base);

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) {
                return redirect("/get-started?mode=login").withNewSession();
            }

            JsonNode me = batch.json.get(0);
            JsonNode messages = batch.json.get(1);
            JsonNode threads = batch.json.get(2);

            String myId = me == null ? "" : me.path("id").asText("");

            // The title and open/closed state come from the matching row in the conversation list.
            String title = "Conversation";
            boolean open = true;
            if (threads != null) {
                for (JsonNode n : listOf(threads)) {
                    if (idOf(n).equals(id)) {
                        title = title(n);
                        String status = n.path("status").asText("");
                        open = status.isEmpty() || status.equals("OPEN");
                        break;
                    }
                }
            }

            String nextCursor = messages == null ? "" : messages.path("nextCursor").asText("");
            boolean hasOlder = messages != null && messages.path("hasNext").asBoolean(false) && !nextCursor.isEmpty();

            ThreadView view = new ThreadView(
                    title,
                    roommate ? "Roommate chat" : "Home enquiry",
                    id,
                    roommate ? "ROOMMATE" : "RENTAL",
                    open,
                    "/dashboard/messages/" + (roommate ? "roommate" : "home") + "/" + id + "/older",
                    hasOlder,
                    nextCursor,
                    toMessages(messages, myId),
                    messages == null);

            return withFreshSession(request, batch, ok(views.html.thread.render(role, view, request)));
        });
    }

    // ---------- Older messages (the page calls this with fetch) ----------

    public CompletionStage<Result> older(Http.Request request, String kind, String id, String cursor) {
        String role = roleOf(request);
        if (role.isEmpty()) {
            return CompletableFuture.completedFuture(unauthorized(error("expired")));
        }
        if (!isSafeId(id) || !allowed(role, kind) || cursor.isEmpty() || cursor.length() > 300) {
            return CompletableFuture.completedFuture(badRequest(error("invalid")));
        }

        String base = threadsBase(role, kind.equals("roommate"));
        String path = base + "/" + id + "/messages?" + CURSOR_PARAM + "="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8);

        return api.getAll(request, List.of("/api/v1/profile/me", path)).thenApply(batch -> {
            if (batch.expired) {
                return unauthorized(error("expired"));
            }
            JsonNode me = batch.json.get(0);
            JsonNode page = batch.json.get(1);
            if (page == null) {
                return withFreshSession(request, batch, status(502, error("unavailable")));
            }

            String myId = me == null ? "" : me.path("id").asText("");
            String nextCursor = page.path("nextCursor").asText("");
            boolean hasNext = page.path("hasNext").asBoolean(false) && !nextCursor.isEmpty();

            ArrayNode list = Json.newArray();
            for (Msg m : toMessages(page, myId)) {
                ObjectNode node = Json.newObject();
                node.put("id", m.id());
                node.put("text", m.text());
                node.put("time", m.time());
                node.put("mine", m.mine());
                node.put("delivered", m.delivered());
                node.put("masked", m.masked());
                list.add(node);
            }

            ObjectNode reply = Json.newObject();
            reply.set("messages", list);
            reply.put("hasNext", hasNext);
            reply.put("nextCursor", hasNext ? nextCursor : "");
            return withFreshSession(request, batch, ok(reply));
        });
    }

    // ---------- Helpers ----------

    private static String threadsBase(String role, boolean roommate) {
        return "/api/v1/" + role.toLowerCase(Locale.ROOT)
                + (roommate ? "/roommate-chat" : "/rental-chat") + "/threads";
    }

    /** Turns a page of backend messages into rows for the page, oldest first, like the app. */
    private static List<Msg> toMessages(JsonNode messages, String myId) {
        List<JsonNode> raw = new ArrayList<>();
        if (messages != null) raw.addAll(listOf(messages));
        raw.sort(Comparator.comparing((JsonNode n) -> n.path("createdAt").asText(""))
                .thenComparing(n -> n.path("id").asText("")));

        List<Msg> list = new ArrayList<>();
        for (JsonNode n : raw) {
            String text = firstText(n, "content", "body", "text", "message");
            String messageId = n.path("id").asText("");
            if (text.isEmpty() || !isSafeId(messageId)) continue;

            boolean mine = !myId.isEmpty() && n.path("senderId").asText("").equals(myId);
            JsonNode deliveredAt = n.path("deliveredAt");
            boolean delivered = !deliveredAt.isMissingNode() && !deliveredAt.isNull()
                    && !deliveredAt.asText("").isEmpty();

            list.add(new Msg(
                    messageId,
                    text,
                    clock(n.path("createdAt").asText("")),
                    mine,
                    delivered,
                    n.path("contactMasked").asBoolean(false)));
        }
        return list;
    }

    private static JsonNode error(String code) {
        ObjectNode node = Json.newObject();
        node.put("error", code);
        return node;
    }

    private static String roleOf(Http.Request request) {
        boolean signedIn = request.session().get("accessToken").isPresent();
        String role = request.session().get("role").orElse("");
        return (signedIn && ROLES.contains(role)) ? role : "";
    }

    private static List<String> pathsFor(String role) {
        if (role.equals("LANDLORD")) {
            return List.of("/api/v1/landlord/rental-chat/threads");
        }
        if (role.equals("SUBLESSOR")) {
            return List.of("/api/v1/sublessor/roommate-chat/threads");
        }
        return List.of(
                "/api/v1/renter/rental-chat/threads",
                "/api/v1/renter/roommate-chat/threads");
    }

    /** Which kinds of chat each role may open. */
    private static boolean allowed(String role, String kind) {
        if (role.equals("LANDLORD")) return kind.equals("home");
        if (role.equals("SUBLESSOR")) return kind.equals("roommate");
        return kind.equals("home") || kind.equals("roommate");
    }

    private static boolean isSafeId(String id) {
        return id != null && id.matches("[A-Za-z0-9-]{1,64}");
    }

    /** The backend may return a plain list or an object with a "content" or "items" list. */
    private static List<JsonNode> listOf(JsonNode json) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode list = json.isArray() ? json : (json.has("content") ? json.path("content") : json.path("items"));
        if (list.isArray()) list.forEach(out::add);
        return out;
    }

    private static String idOf(JsonNode n) {
        return firstText(n, "id", "conversationId", "threadId");
    }

    private static String title(JsonNode n) {
        String value = firstText(n, "counterpartyName", "landlordName", "renterName", "title");
        return value.isEmpty() ? "Conversation" : value;
    }

    private static String preview(JsonNode n) {
        JsonNode last = n.path("lastMessage");
        if (last.isTextual()) return last.asText();
        if (last.isObject()) {
            String value = firstText(last, "content", "body", "text", "message");
            if (!value.isEmpty()) return value;
        }
        return n.path("lastMessagePreview").asText("");
    }

    private static String firstText(JsonNode n, String... fields) {
        for (String field : fields) {
            String value = n.path(field).asText("");
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    /** Kenyan time, like the app. */
    private static String clock(String iso) {
        try {
            return CLOCK.format(Instant.parse(iso));
        } catch (Exception e) {
            return "";
        }
    }

    /** Saves refreshed tokens to the session when the backend renewed them during the call. */
    private static Result withFreshSession(Http.Request request, AuthedApi.Batch batch, Result page) {
        if (!batch.refreshed()) return page;
        Map<String, String> session = new HashMap<>(request.session().data());
        session.put("accessToken", batch.accessToken);
        session.put("refreshToken", batch.refreshToken);
        return page.withSession(session);
    }
}