package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.ChatRelay;
import services.MakaoApi;

public class ChatController extends Controller {

    private static final Set<String> ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private record Who(String access, String role) {}

    private final ChatRelay relay;
    private final MakaoApi api;

    @Inject
    public ChatController(ChatRelay relay, MakaoApi api) {
        this.relay = relay;
        this.api = api;
    }

    /** Sends one message. The browser calls this with fetch and gets JSON back. */
    public CompletionStage<Result> send(Http.Request request) {
        Who who = who(request);
        if (who == null) return done(unauthorized(error("expired", "Your session expired. Please sign in again.")));

        JsonNode body = request.body().asJson();
        if (body == null) return done(badRequest(error("invalid", "Message not sent.")));

        ChatRelay.Channel channel = channelFor(who.role(), body.path("channel").asText(""));
        String threadId = body.path("threadId").asText("");
        String clientId = body.path("clientMessageId").asText("");
        String content = body.path("content").asText("");

        if (channel == null || !ID.matcher(threadId).matches() || !isUuid(clientId)
                || content.isBlank() || content.length() > 2000) {
            return done(badRequest(error("invalid", "Message not sent. Check what you typed and try again.")));
        }

        String refresh = request.session().get("refreshToken").orElse("");

        return relay.send(who.access(), channel, threadId, clientId, content).thenCompose(first -> {
            // Only a rejected login is worth a renewal. Anything else is a real failure.
            if (first.ok() || !"auth".equals(first.reason()) || refresh.isEmpty()) {
                return done(sendResult(first, request, null));
            }

            return renew(refresh).thenCompose(tokens -> {
                if (tokens == null) {
                    return done(unauthorized(error("expired", "Your session expired. Please sign in again.")));
                }
                // The old refresh token is used up now, so the new pair must be saved whatever happens next.
                return relay.send(tokens[0], channel, threadId, clientId, content)
                        .thenApply(second -> sendResult(second, request, tokens));
            });
        });
    }

    /** Tells the backend that these incoming messages arrived (the second tick for the sender). */
    public CompletionStage<Result> delivered(Http.Request request) {
        Who who = who(request);
        if (who == null) return done(unauthorized(error("expired", "Your session expired. Please sign in again.")));

        JsonNode body = request.body().asJson();
        if (body == null) return done(badRequest(error("invalid", "Invalid request.")));

        ChatRelay.Channel channel = channelFor(who.role(), body.path("channel").asText(""));
        String threadId = body.path("threadId").asText("");
        List<String> ids = new ArrayList<>();
        for (JsonNode id : body.path("messageIds")) {
            String value = id.asText("");
            if (ID.matcher(value).matches() && ids.size() < 100) ids.add(value);
        }
        if (channel == null || !ID.matcher(threadId).matches() || ids.isEmpty()) {
            return done(badRequest(error("invalid", "Invalid request.")));
        }

        // No renewal here: the page is reloaded regularly, and that renews the login. Doing it in two
        // places at once could use the same refresh token twice, which the backend treats as theft.
        return relay.delivered(who.access(), channel, threadId, ids).thenApply(outcome -> {
            if (!outcome.ok()) return failure(outcome.reason());
            ObjectNode reply = Json.newObject();
            reply.put("ok", true);
            return ok(reply);
        });
    }

    // ---------- Renewal ----------

    /** Asks the backend for a new pair of tokens. Returns {access, refresh}, or null if it said no. */
    private CompletionStage<String[]> renew(String refreshToken) {
        ObjectNode body = Json.newObject();
        body.put("refreshToken", refreshToken);

        return api.post("/api/v1/auth/refresh", body).thenApply(result -> {
            String access = result.text("accessToken");
            String refresh = result.text("refreshToken");
            if (!result.isSuccess() || access.isEmpty() || refresh.isEmpty()) return null;
            return new String[]{access, refresh};
        });
    }

    /** The answer for a send, saving renewed tokens in the session if there are any. */
    private static Result sendResult(ChatRelay.Outcome outcome, Http.Request request, String[] tokens) {
        Result result;
        if (outcome.ok()) {
            ObjectNode reply = Json.newObject();
            reply.put("ok", true);
            reply.set("message", outcome.message());
            result = ok(reply);
        } else {
            result = failure(outcome.reason());
        }
        if (tokens == null) return result;

        Map<String, String> session = new HashMap<>(request.session().data());
        session.put("accessToken", tokens[0]);
        session.put("refreshToken", tokens[1]);
        return result.withSession(session);
    }

    // ---------- Helpers ----------

    private static Who who(Http.Request request) {
        String access = request.session().get("accessToken").orElse("");
        String role = request.session().get("role").orElse("");
        return access.isEmpty() || !ROLES.contains(role) ? null : new Who(access, role);
    }

    /** Landlords and sublessors are fixed by their account. Only a renter chooses, per conversation. */
    private static ChatRelay.Channel channelFor(String role, String requested) {
        if (role.equals("LANDLORD")) return ChatRelay.Channel.RENTAL;
        if (role.equals("SUBLESSOR")) return ChatRelay.Channel.ROOMMATE;
        if (requested.equals("RENTAL")) return ChatRelay.Channel.RENTAL;
        if (requested.equals("ROOMMATE")) return ChatRelay.Channel.ROOMMATE;
        return null;
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static Result failure(String reason) {
        switch (reason == null ? "" : reason) {
            case "auth":
                return unauthorized(error("expired", "Your session expired. Please sign in again."));
            case "rejected":
                return status(422, error("rejected", "The server could not send this message. Check your access and try again."));
            case "timeout":
                return status(504, error("timeout", "The server took too long. Please try again."));
            default:
                return status(502, error("unavailable", "Chat is unavailable right now. Please try again."));
        }
    }

    private static JsonNode error(String code, String message) {
        ObjectNode node = Json.newObject();
        node.put("error", code);
        node.put("message", message);
        return node;
    }

    private static CompletionStage<Result> done(Result result) {
        return CompletableFuture.completedFuture(result);
    }
}