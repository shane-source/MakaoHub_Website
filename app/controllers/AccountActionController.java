package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class AccountActionController extends Controller {

    private static final Logger LOG = LoggerFactory.getLogger(AccountActionController.class);

    private static final Set<String> ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");
    private static final String LOGIN = "/get-started?mode=login";
    private static final String START = "/api/v1/profile/me/account-action/start";
    private static final String CONFIRM = "/api/v1/profile/me/account-action/confirm";

    private final AuthedApi api;

    @Inject
    public AccountActionController(AuthedApi api) {
        this.api = api;
    }

    // ---------- The page: first "send code", then "enter code" ----------

    public Result page(Http.Request request, String action) {
        if (roleOf(request).isEmpty()) return redirect(LOGIN);
        String code = codeFor(action);
        if (code == null) return redirect("/dashboard/privacy");

        boolean waiting = code.equals(request.session().get("acctAction").orElse(""))
                && request.session().get("acctChallenge").isPresent();
        String masked = waiting ? request.session().get("acctMasked").orElse("") : "";
        String error = request.flash().get("acct").orElse("");

        return ok(views.html.accountAction.render(roleOf(request), action, waiting, masked, error, request));
    }

    // ---------- Step 1: ask the backend to email a code ----------

    public CompletionStage<Result> start(Http.Request request, String action) {
        String role = roleOf(request);
        if (role.isEmpty()) return completed(redirect(LOGIN));
        String code = codeFor(action);
        if (code == null) return completed(redirect("/dashboard/privacy"));

        String page = "/dashboard/account/" + action;
        if (code.equals("DELETE") && !"yes".equals(field(request, "agree"))) {
            return completed(redirect(page).flashing("acct", "agree"));
        }

        String access = request.session().get("accessToken").orElse("");
        String refresh = request.session().get("refreshToken").orElse("");

        return api.getAll(request, List.of("/api/v1/profile/me")).thenCompose(batch -> {
            if (batch.expired) return completed(redirect(LOGIN).withNewSession());

            String a1 = batch.refreshed() ? batch.accessToken : access;
            String r1 = batch.refreshed() ? batch.refreshToken : refresh;
            String email = text(batch.json.get(0), "email");
            if (email.isEmpty()) {
                LOG.warn("account-action start: the profile has no email");
                return completed(failed(request, page, "sendfailed", batch.refreshed() ? a1 : null, r1));
            }

            ObjectNode body = Json.newObject();
            body.put("action", code);
            body.put("email", email);

            return api.post(request, a1, r1, START, body).thenApply(res -> {
                if (res.expired) return redirect(LOGIN).withNewSession();

                boolean renewed = batch.refreshed() || res.refreshed();
                String a2 = res.refreshed() ? res.accessToken : a1;
                String r2 = res.refreshed() ? res.refreshToken : r1;

                String challenge = text(res.json, "challengeId");
                if (!res.ok() || challenge.isEmpty()) {
                    LOG.warn("account-action start failed: status={} body={}", res.status, res.json);
                    return failed(request, page, "sendfailed", renewed ? a2 : null, r2);
                }

                Map<String, String> session = base(request, renewed ? a2 : null, r2);
                session.put("acctAction", code);
                session.put("acctChallenge", challenge);
                session.put("acctMasked", text(res.json, "maskedEmail"));
                return redirect(page).withSession(session);
            });
        });
    }

    // ---------- Step 2: check the code ----------

    public CompletionStage<Result> confirm(Http.Request request, String action) {
        String role = roleOf(request);
        if (role.isEmpty()) return completed(redirect(LOGIN));
        String code = codeFor(action);
        if (code == null) return completed(redirect("/dashboard/privacy"));

        String page = "/dashboard/account/" + action;
        String challenge = request.session().get("acctChallenge").orElse("");
        if (challenge.isEmpty() || !code.equals(request.session().get("acctAction").orElse(""))) {
            return completed(redirect(page));   // nothing was started, so begin again
        }

        String otp = field(request, "code").replaceAll("\\s", "");
        if (!otp.matches("[0-9]{4,8}")) {
            return completed(redirect(page).flashing("acct", "badcode"));
        }

        String access = request.session().get("accessToken").orElse("");
        String refresh = request.session().get("refreshToken").orElse("");

        return api.getAll(request, List.of("/api/v1/profile/me")).thenCompose(batch -> {
            if (batch.expired) return completed(redirect(LOGIN).withNewSession());

            String a1 = batch.refreshed() ? batch.accessToken : access;
            String r1 = batch.refreshed() ? batch.refreshToken : refresh;
            String email = text(batch.json.get(0), "email");
            if (email.isEmpty()) {
                LOG.warn("account-action confirm: the profile has no email");
                return completed(failed(request, page, "badcode", batch.refreshed() ? a1 : null, r1));
            }

            ObjectNode body = Json.newObject();
            body.put("action", code);
            body.put("challengeId", challenge);
            body.put("code", otp);
            body.put("email", email);

            return api.post(request, a1, r1, CONFIRM, body).thenApply(res -> {
                if (res.expired) return redirect(LOGIN).withNewSession();
                if (res.ok()) {
                    // Done. The account is closed or being reviewed, so the website session ends here.
                    return redirect("/account/done/" + action).withNewSession();
                }
                LOG.warn("account-action confirm failed: status={} body={}", res.status, res.json);
                boolean renewed = batch.refreshed() || res.refreshed();
                String a2 = res.refreshed() ? res.accessToken : a1;
                String r2 = res.refreshed() ? res.refreshToken : r1;
                // Wrong or expired code: stay on the code step so they can try again.
                return failed(request, page, "badcode", renewed ? a2 : null, r2);
            });
        });
    }

    // ---------- The last page, shown after signing out ----------

    public Result done(String action) {
        if (codeFor(action) == null) return redirect("/");
        return ok(views.html.accountActionDone.render(action));
    }

    // ---------- Helpers ----------

    /** "deactivate" or "delete" become the backend's words. Anything else is refused. */
    private static String codeFor(String action) {
        if (action.equals("deactivate")) return "DEACTIVATE";
        if (action.equals("delete")) return "DELETE";
        return null;
    }

    private static String roleOf(Http.Request request) {
        boolean signedIn = request.session().get("accessToken").isPresent();
        String role = request.session().get("role").orElse("");
        return (signedIn && ROLES.contains(role)) ? role : "";
    }

    private static String field(Http.Request request, String name) {
        Map<String, String[]> form = request.body().asFormUrlEncoded();
        if (form == null || !form.containsKey(name) || form.get(name).length == 0) return "";
        return form.get(name)[0].trim();
    }

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    private static Map<String, String> base(Http.Request request, String access, String refresh) {
        Map<String, String> session = new HashMap<>(request.session().data());
        if (access != null) {
            session.put("accessToken", access);
            session.put("refreshToken", refresh);
        }
        return session;
    }

    /** Back to the page with a short message, saving renewed tokens if there are any. */
    private static Result failed(Http.Request request, String page, String reason, String access, String refresh) {
        Result back = redirect(page).flashing("acct", reason);
        return access == null ? back : back.withSession(base(request, access, refresh));
    }

    private static CompletionStage<Result> completed(Result result) {
        return CompletableFuture.completedFuture(result);
    }
}