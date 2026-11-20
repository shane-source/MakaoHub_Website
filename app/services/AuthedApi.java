package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.libs.ws.WSClient;
import play.libs.ws.WSRequest;
import play.libs.ws.WSResponse;
import play.mvc.Http;


/**
 * Signed-in calls to the backend. Reads the access token from the website session,
 * refreshes it once if the backend answers 401, and reports when the person must sign in again.
 */
@Singleton
public class AuthedApi {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final WSClient ws;
    private final String baseUrl;
    private final ClientIdentity identity;

    @Inject
    public AuthedApi(WSClient ws, Config config, ClientIdentity identity) {
        this.ws = ws;
        this.identity = identity;
        String url = config.getString("makao.api.baseUrl");
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    // ---------- Result types ----------

    /** Answers for the requested paths, in the same order. A failed call is null. */
    public static class Batch {
        public final List<JsonNode> json;
        public final boolean expired;          // true: the person has to sign in again
        public final String accessToken;       // set only when the tokens were refreshed
        public final String refreshToken;

        private Batch(List<JsonNode> json, boolean expired, String accessToken, String refreshToken) {
            this.json = json;
            this.expired = expired;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }

        public boolean refreshed() {
            return accessToken != null;
        }
    }

    /** The answer to one POST or PATCH. */
    public static class Posted {
        public final int status;
        public final JsonNode json;            // may be null, for example on 204
        public final boolean expired;          // true: the person has to sign in again
        public final String accessToken;       // set only when the tokens were refreshed
        public final String refreshToken;

        private Posted(Single single, boolean expired, String accessToken, String refreshToken) {
            this.status = single.status;
            this.json = single.json;
            this.expired = expired;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public boolean refreshed() {
            return accessToken != null;
        }

        public String text(String field) {
            return json == null ? "" : json.path(field).asText("");
        }

        /** The backend's own message when it gave one, otherwise the fallback. */
        public String message(String fallback) {
            String value = text("message");
            if (value.isEmpty()) value = text("detail");
            if (value.isEmpty()) return fallback;
            return value.length() > 200 ? value.substring(0, 200) : value;
        }
    }

    /** The answer to one PUT or DELETE. */
    public static class WriteResult {
        public final int status;
        public final JsonNode json;            // may be null, for example on 204
        public final boolean expired;          // true: the person has to sign in again
        public final String accessToken;       // set only when the tokens were refreshed
        public final String refreshToken;

        private WriteResult(int status, JsonNode json, boolean expired, String accessToken, String refreshToken) {
            this.status = status;
            this.json = json;
            this.expired = expired;
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }

        public boolean refreshed() {
            return accessToken != null;
        }
    }

    private static class Single {
        final int status;
        final JsonNode json;
        Single(int status, JsonNode json) { this.status = status; this.json = json; }
    }

    private static class Round {
        final List<JsonNode> json;
        final boolean unauthorized;
        Round(List<JsonNode> json, boolean unauthorized) { this.json = json; this.unauthorized = unauthorized; }
    }

    private static class Tokens {
        final String access;
        final String refresh;
        Tokens(String access, String refresh) { this.access = access; this.refresh = refresh; }
    }

    private static <T> CompletionStage<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }

    // ---------- GET ----------

    /** GET every path at the same time with the person's token. */
    public CompletionStage<Batch> getAll(Http.Request request, List<String> paths) {
        String access = request.session().get("accessToken").orElse("");
        String refresh = request.session().get("refreshToken").orElse("");

        return fetchAll(access, paths).thenCompose(first -> {
            if (!first.unauthorized) {
                return done(new Batch(first.json, false, null, null));
            }
            if (refresh.isEmpty()) {
                return done(new Batch(first.json, true, null, null));
            }
            return refreshTokens(refresh, identity.ipOf(request)).thenCompose(tokens -> {
                if (tokens == null) {
                    return done(new Batch(first.json, true, null, null));
                }
                return fetchAll(tokens.access, paths).thenApply(second -> second.unauthorized
                        ? new Batch(second.json, true, null, null)
                        : new Batch(second.json, false, tokens.access, tokens.refresh));
            });
        });
    }

    private CompletionStage<Round> fetchAll(String access, List<String> paths) {
        List<CompletableFuture<Single>> calls = new ArrayList<>();
        for (String path : paths) {
            calls.add(fetchOne(access, path).toCompletableFuture());
        }
        return CompletableFuture.allOf(calls.toArray(new CompletableFuture[0])).thenApply(finished -> {
            List<JsonNode> out = new ArrayList<>();
            boolean unauthorized = false;
            for (CompletableFuture<Single> call : calls) {
                Single single = call.join();
                out.add(single.json);
                if (single.status == 401) unauthorized = true;
            }
            return new Round(out, unauthorized);
        });
    }

    private CompletionStage<Single> fetchOne(String access, String path) {
        return ws.url(baseUrl + path)
                .addHeader("Authorization", "Bearer " + access)
                .setRequestTimeout(TIMEOUT)
                .get()
                .thenApply(response -> {
                    JsonNode body = null;
                    if (response.getStatus() == 200) {
                        try {
                            body = response.asJson();
                        } catch (Exception ignored) {
                            body = null;
                        }
                    }
                    return new Single(response.getStatus(), body);
                })
                .exceptionally(error -> new Single(0, null));
    }

    /** GET one path and keep the status, so callers can tell "not allowed" (403) from "not found". */
    public CompletionStage<WriteResult> getWithStatus(Http.Request request, String path) {
        String access = request.session().get("accessToken").orElse("");
        String refresh = request.session().get("refreshToken").orElse("");

        return fetchOne(access, path).thenCompose(first -> {
            if (first.status != 401) {
                return done(new WriteResult(first.status, first.json, false, null, null));
            }
            if (refresh.isEmpty()) {
                return done(new WriteResult(401, null, true, null, null));
            }
            return refreshTokens(refresh, identity.ipOf(request)).thenCompose(tokens -> {
                if (tokens == null) {
                    return done(new WriteResult(401, null, true, null, null));
                }
                return fetchOne(tokens.access, path).thenApply(second -> second.status == 401
                        ? new WriteResult(401, null, true, null, null)
                        : new WriteResult(second.status, second.json, false, tokens.access, tokens.refresh));
            });
        });
    }

    // ---------- POST and PATCH ----------

    /** POST with the tokens from the website session. The body may be null. */
    public CompletionStage<Posted> post(Http.Request request, String path, JsonNode body) {
        return call("POST",
                request.session().get("accessToken").orElse(""),
                request.session().get("refreshToken").orElse(""),
                path, body, null, identity.ipOf(request));
    }

    /** POST with tokens given directly. The body may be null. */
    public CompletionStage<Posted> post(String access, String refresh, String path, JsonNode body) {
        return call("POST", access, refresh, path, body, null, null);
    }

    /** POST with tokens given directly, on behalf of the visitor who made this request. */
    public CompletionStage<Posted> post(Http.RequestHeader request, String access, String refresh,
                                        String path, JsonNode body) {
        return call("POST", access, refresh, path, body, null, identity.ipOf(request));
    }

    /** Same as post(request, path, body). Kept because other pages call it by this name. */
    public CompletionStage<Posted> postJson(Http.Request request, String path, JsonNode body) {
        return post(request, path, body);
    }

    /** POST with an Idempotency-Key, so a retry can never make a second payment or change. */
    public CompletionStage<Posted> postKeyed(Http.Request request, String path, JsonNode body, String key) {
        return call("POST",
                request.session().get("accessToken").orElse(""),
                request.session().get("refreshToken").orElse(""),
                path, body, key, identity.ipOf(request));
    }

    /** Same, with tokens given directly (used after an earlier call in the same request renewed them). */
    public CompletionStage<Posted> postKeyed(String access, String refresh, String path, JsonNode body, String key) {
        return call("POST", access, refresh, path, body, key, null);
    }

    /** PATCH with no body, with the tokens from the website session. */
    public CompletionStage<Posted> patch(Http.Request request, String path) {
        return call("PATCH",
                request.session().get("accessToken").orElse(""),
                request.session().get("refreshToken").orElse(""),
                path, null, null, identity.ipOf(request));
    }

    /**
     * Sends the request. A 401 means nothing was processed, so it is safe to renew the tokens once
     * and send the same request again.
     */
    private CompletionStage<Posted> call(String method, String access, String refresh, String path,
                                         JsonNode body, String key, String ip) {
        return send(method, access, path, body, key, ip).thenCompose(first -> {
            if (first.status != 401) {
                return done(new Posted(first, false, null, null));
            }
            if (refresh == null || refresh.isEmpty()) {
                return done(new Posted(first, true, null, null));
            }
            return refreshTokens(refresh, ip).thenCompose(tokens -> {
                if (tokens == null) {
                    return done(new Posted(first, true, null, null));
                }
                return send(method, tokens.access, path, body, key, ip).thenApply(second -> second.status == 401
                        ? new Posted(second, true, null, null)
                        : new Posted(second, false, tokens.access, tokens.refresh));
            });
        });
    }

    private CompletionStage<Single> send(String method, String access, String path, JsonNode body, String key,
                                         String ip) {
        WSRequest req = identity.tag(ws.url(baseUrl + path), ip)
                .addHeader("Authorization", "Bearer " + access)
                .setRequestTimeout(TIMEOUT);
        if (key != null) {
            req = req.addHeader("Idempotency-Key", key);
        }

        CompletionStage<? extends WSResponse> response;
        if (method.equals("PATCH")) {
            response = body == null ? req.patch("") : req.patch(body);
        } else {
            response = body == null ? req.post("") : req.post(body);
        }

        return response
                .thenApply(answer -> {
                    JsonNode json = null;
                    try {
                        String text = answer.getBody();
                        if (text != null && !text.isBlank()) json = answer.asJson();
                    } catch (Exception ignored) {
                        json = null;
                    }
                    return new Single(answer.getStatus(), json);
                })
                .exceptionally(error -> new Single(0, null));
    }

    // ---------- PUT and DELETE ----------

    /** PUT with the person's token, with the same refresh-once rule as the other calls. */
    public CompletionStage<WriteResult> put(Http.Request request, String path, JsonNode body) {
        return write(request, "PUT", path, body);
    }

    /** DELETE with the person's token, with the same refresh-once rule as the other calls. */
    public CompletionStage<WriteResult> delete(Http.Request request, String path) {
        return write(request, "DELETE", path, null);
    }

    private CompletionStage<WriteResult> write(Http.Request request, String verb, String path, JsonNode body) {
        String access = request.session().get("accessToken").orElse("");
        String refresh = request.session().get("refreshToken").orElse("");

        return writeOne(verb, access, path, body).thenCompose(first -> {
            if (first.status != 401) {
                return CompletableFuture.completedFuture(
                        new WriteResult(first.status, first.json, false, null, null));
            }
            if (refresh.isEmpty()) {
                return CompletableFuture.completedFuture(
                        new WriteResult(first.status, first.json, true, null, null));
            }
            return refreshTokens(refresh, identity.ipOf(request)).thenCompose(tokens -> {
                if (tokens == null) {
                    return CompletableFuture.completedFuture(
                            new WriteResult(first.status, first.json, true, null, null));
                }
                return writeOne(verb, tokens.access, path, body).thenApply(second -> second.status == 401
                        ? new WriteResult(second.status, second.json, true, null, null)
                        : new WriteResult(second.status, second.json, false, tokens.access, tokens.refresh));
            });
        });
    }

    private CompletionStage<Single> writeOne(String verb, String access, String path, JsonNode body) {
        WSRequest call = ws.url(baseUrl + path)
                .addHeader("Authorization", "Bearer " + access)
                .setRequestTimeout(TIMEOUT);

        if (verb.equals("PUT")) {
            return call.put(body == null ? Json.newObject() : body)
                    .thenApply(AuthedApi::single)
                    .exceptionally(error -> new Single(0, null));
        }
        return call.delete()
                .thenApply(AuthedApi::single)
                .exceptionally(error -> new Single(0, null));
    }

    private static Single single(WSResponse response) {
        JsonNode json = null;
        try {
            json = response.asJson();
        } catch (Exception ignored) {
            json = null;
        }
        return new Single(response.getStatus(), json);
    }

    // ---------- Token renewal ----------

    private CompletionStage<Tokens> refreshTokens(String refreshToken, String ip) {
        ObjectNode body = Json.newObject();
        body.put("refreshToken", refreshToken);
        return identity.tag(ws.url(baseUrl + "/api/v1/auth/refresh"), ip)
                .setRequestTimeout(TIMEOUT)
                .post(body)
                .thenApply(response -> {
                    if (response.getStatus() != 200) return (Tokens) null;
                    JsonNode json = response.asJson();
                    String access = json.path("accessToken").asText("");
                    String refresh = json.path("refreshToken").asText("");
                    return (access.isEmpty() || refresh.isEmpty()) ? null : new Tokens(access, refresh);
                })
                .exceptionally(error -> null);
    }
}