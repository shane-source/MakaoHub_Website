package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.libs.ws.WSClient;
import play.libs.ws.WSRequest;
import play.libs.ws.WSResponse;

/** Signed-in calls to the backend. Renews the login once if it has expired. */
@Singleton
public class OwnerApi {

    /** access and refresh are set only when the login was renewed during this call. */
    public record Reply(int status, JsonNode json, boolean expired, String access, String refresh) {

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public String text(String field) {
            if (json == null) return "";
            JsonNode node = json.get(field);
            return (node == null || node.isNull()) ? "" : node.asText("");
        }

        /** A message that is safe to show to the person. */
        public String message() {
            if (json != null) {
                for (String key : new String[]{"message", "detail"}) {
                    JsonNode node = json.get(key);
                    if (node != null && node.isTextual() && !node.asText().isBlank()) return node.asText();
                }
            }
            if (status == 0) return "We couldn't reach the server. Please try again in a moment.";
            if (status == 401) return "Your session expired. Please sign in again.";
            if (status == 403) return "Your account can't do this yet. Check that your verification is complete.";
            if (status == 404) return "We couldn't find that listing.";
            if (status == 409) return "That can't be done while the listing is in its current state.";
            if (status == 400 || status == 422) return "Some details were not accepted. Check the fields and try again.";
            if (status >= 500) return "Something went wrong on our side. Please try again.";
            return "We couldn't complete that. Please try again.";
        }
    }

    private record Raw(int status, JsonNode json) {}

    private final WSClient ws;
    private final String base;

    @Inject
    public OwnerApi(WSClient ws, Config config) {
        this.ws = ws;
        String url = config.getString("makao.api.baseUrl");
        this.base = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /**
     * One signed-in call. Pass the tokens in, and use the ones in the reply for any later call in a chain,
     * because the backend replaces the refresh token each time it is used.
     */
    public CompletionStage<Reply> call(String access, String refresh, String method, String path, JsonNode body) {
        return send(access, method, path, body).thenCompose(first -> {
            if (first.status() != 401) {
                return done(new Reply(first.status(), first.json(), false, null, null));
            }
            if (refresh == null || refresh.isEmpty()) {
                return done(new Reply(401, first.json(), true, null, null));
            }
            return renew(refresh).thenCompose(tokens -> {
                if (tokens == null) return done(new Reply(401, first.json(), true, null, null));
                return send(tokens[0], method, path, body).thenApply(second -> second.status() == 401
                        ? new Reply(401, second.json(), true, null, null)
                        : new Reply(second.status(), second.json(), false, tokens[0], tokens[1]));
            });
        });
    }

    private CompletionStage<Raw> send(String access, String method, String path, JsonNode body) {
        WSRequest request = ws.url(base + path)
                .addHeader("Authorization", "Bearer " + access)
                .addHeader("ngrok-skip-browser-warning", "true")
                .setRequestTimeout(Duration.ofSeconds(20));
        JsonNode payload = body == null ? Json.newObject() : body;

        CompletionStage<WSResponse> response;
        switch (method) {
            case "GET":    response = request.get(); break;
            case "POST":   response = request.post(payload); break;
            case "PUT":    response = request.put(payload); break;
            case "PATCH":  response = request.patch(payload); break;
            case "DELETE": response = request.delete(); break;
            default: throw new IllegalArgumentException("Unsupported method");
        }
        return response
                .thenApply(r -> new Raw(r.getStatus(), parse(r)))
                .exceptionally(error -> new Raw(0, null));
    }

    private CompletionStage<String[]> renew(String refreshToken) {
        ObjectNode body = Json.newObject();
        body.put("refreshToken", refreshToken);
        return ws.url(base + "/api/v1/auth/refresh")
                .addHeader("ngrok-skip-browser-warning", "true")
                .setRequestTimeout(Duration.ofSeconds(15))
                .post(body)
                .thenApply(r -> {
                    JsonNode json = parse(r);
                    if (r.getStatus() < 200 || r.getStatus() >= 300 || json == null) return null;
                    String access = json.path("accessToken").asText("");
                    String refresh = json.path("refreshToken").asText("");
                    return (access.isEmpty() || refresh.isEmpty()) ? null : new String[]{access, refresh};
                })
                .exceptionally(error -> null);
    }

    private static JsonNode parse(WSResponse response) {
        try {
            return response.asJson();
        } catch (Exception e) {
            return null;
        }
    }

    private static <T> CompletionStage<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }
}