package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.MakaoApi;

public class AccountController extends Controller {

    private final MakaoApi api;

    @Inject
    public AccountController(MakaoApi api) {
        this.api = api;
    }

    // Tells the backend to cancel the refresh token, then clears the website session.
    public CompletionStage<Result> logout(Http.Request request) {
        String refreshToken = request.session().get("refreshToken").orElse("");

        if (refreshToken.isEmpty()) {
            return CompletableFuture.completedFuture(redirect("/").withNewSession());
        }

        ObjectNode body = Json.newObject();
        body.put("refreshToken", refreshToken);

        // The person is signed out of the website even if the backend can't be reached.
        return api.post("/api/v1/auth/logout", body)
                .thenApply(result -> redirect("/").withNewSession());
    }
}