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
        java.util.Map<String, String[]> form = request.body().asFormUrlEncoded();
        boolean idle = form != null && form.get("idle") != null;
        String last = DashSupport.lastRole(request);
        String signedInRole = request.session().get("role").orElse(last);
        // After inactivity sign-out go back to sign-in as the same role; a normal sign-out lands on "/", which does the same.
        String target = idle
                ? (signedInRole != null && java.util.Set.of("RENTER", "LANDLORD", "SUBLESSOR").contains(signedInRole)
                        ? "/login?role=" + signedInRole + "&idle=1" : "/get-started?mode=login&idle=1")
                : "/";
        if (refresh.isEmpty()) {
            return CompletableFuture.completedFuture(DashSupport.rememberRole(redirect(target).withNewSession(), signedInRole));
        }

        ObjectNode body = Json.newObject();
        body.put("refreshToken", refresh);

        return api.post(request, "/api/v1/auth/logout", body)
                .thenApply(result -> DashSupport.rememberRole(redirect(target).withNewSession(), signedInRole));
    }
}