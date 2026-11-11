package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class SessionController extends Controller {

    private final AuthedApi api;

    @Inject
    public SessionController(AuthedApi api) {
        this.api = api;
    }

    /** Revokes the session on the backend, then clears the website session whatever the answer. */
    public CompletionStage<Result> logout(Http.Request request) {
        String refresh = request.session().get("refreshToken").orElse("");
        if (refresh.isEmpty()) {
            return CompletableFuture.completedFuture(redirect("/").withNewSession());
        }

        ObjectNode body = Json.newObject();
        body.put("refreshToken", refresh);

        return api.post(request, "/api/v1/auth/logout", body)
                .thenApply(result -> redirect("/").withNewSession());
    }
}