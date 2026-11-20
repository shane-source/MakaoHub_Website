package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

/** Passes a question to Jirani, the Makao Hub help assistant, and returns the answer as JSON. */
public class JiraniController extends Controller {

    private final AuthedApi api;

    @Inject
    public JiraniController(AuthedApi api) {
        this.api = api;
    }

    public CompletionStage<Result> chat(Http.Request request) {
        if (DashSupport.roleOf(request) == null) {
            return CompletableFuture.completedFuture(unauthorized(error("Please sign in again.")));
        }

        JsonNode in = request.body().asJson();
        JsonNode list = in == null ? null : in.get("messages");
        if (list == null || !list.isArray() || list.size() == 0 || list.size() > 24) {
            return CompletableFuture.completedFuture(badRequest(error("Ask Jirani a question to get started.")));
        }

        // Only the role and text go on. Anything else the browser sent is dropped.
        ArrayNode messages = Json.newArray();
        for (JsonNode m : list) {
            String role = "assistant".equals(m.path("role").asText("")) ? "assistant" : "user";
            String content = m.path("content").asText("");
            if (content.isBlank()) continue;
            if (content.length() > 1000) content = content.substring(0, 1000);
            ObjectNode node = Json.newObject();
            node.put("role", role);
            node.put("content", content);
            messages.add(node);
        }
        ObjectNode body = Json.newObject();
        body.set("messages", messages);

        return api.post(request, "/api/v1/support/chat", body).thenApply(result -> {
            if (result.expired) return unauthorized(error("Please sign in again."));

            Result reply;
            if (result.ok() && result.json != null && result.json.hasNonNull("reply")) {
                ObjectNode out = Json.newObject();
                out.put("reply", result.json.path("reply").asText(""));
                reply = ok(out);
            } else {
                int status = (result.status == 429 || result.status == 400) ? result.status : 503;
                reply = status(status, error(result.message(
                        "Jirani is busy right now. Please try again, or contact support.")));
            }
            if (result.refreshed()) {
                java.util.Map<String, String> session = new java.util.HashMap<>(request.session().data());
                session.put("accessToken", result.accessToken);
                session.put("refreshToken", result.refreshToken);
                return reply.withSession(session);
            }
            return reply;
        });
    }

    private static ObjectNode error(String message) {
        ObjectNode node = Json.newObject();
        node.put("error", message);
        return node;
    }
}
