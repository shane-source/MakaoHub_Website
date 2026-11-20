package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.ApiResult;
import services.MakaoApi;

/**
 * "Continue with Google". The browser gets a Google ID token from Google's own button; this controller only
 * forwards it. The backend verifies the token, so nothing read here is trusted for access.
 */
public class GoogleAuthController extends Controller {

    private static final play.Logger.ALogger LOG = play.Logger.of(GoogleAuthController.class);

    private static final Set<String> ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");

    private final MakaoApi api;
    private final String clientId;

    @Inject
    public GoogleAuthController(MakaoApi api, Config config) {
        this.api = api;
        this.clientId = config.hasPath("makao.google.clientId") ? config.getString("makao.google.clientId") : "";
    }

    /** Sign in with Google from the sign-in page. */
    public CompletionStage<Result> login(Http.Request request) {
        Map<String, String[]> form = request.body().asFormUrlEncoded();
        String role = text(form, "role");
        String idToken = text(form, "idToken");
        LOG.info("Google login: role={} tokenLength={} configuredClientId={}", role, idToken.length(), !clientId.isEmpty());

        if (!ROLES.contains(role)) {
            LOG.warn("Google login stopped: unknown role '{}'", role);
            return done(redirect("/get-started?mode=login"));
        }
        if (!plausibleToken(idToken)) {
            LOG.warn("Google login stopped: token missing or malformed (length {})", idToken.length());
            return done(backToLogin(role, "Google sign-in didn't complete. Please try again."));
        }

        ObjectNode body = Json.newObject();
        body.put("idToken", idToken);
        body.put("expectedRole", role);

        return api.post(request, "/api/v1/auth/google", body).thenApply(result -> {
            LOG.info("Google login: backend answered status={} code={} message={}",
                    result.status(), result.code(), result.isSuccess() ? "" : result.errorMessage());
            if (result.status() == 0) {
                LOG.error("Google login: the backend could not be reached (check makao.api.baseUrl and that it is running)");
            }
            if (result.isSuccess()) {
                return startCode(request, result, "REACTIVATION".equals(result.text("purpose")) ? "reactivation" : "googleLogin");
            }
            if (result.status() == 409 && "GOOGLE_REGISTRATION_REQUIRED".equals(result.code())) {
                // No account yet: collect the phone number and consent, then create it.
                return ok(completePage(request, idToken, role, "", "Create your account with Google to continue."));
            }
            return backToLogin(role, result.errorMessage());
        });
    }

    /** Start sign-up with Google from the create-account page. */
    public Result continueSignUp(Http.Request request) {
        Map<String, String[]> form = request.body().asFormUrlEncoded();
        String role = text(form, "role");
        String idToken = text(form, "idToken");
        LOG.info("Google sign-up start: role={} tokenLength={}", role, idToken.length());

        if (!ROLES.contains(role)) return redirect("/get-started");
        if (!plausibleToken(idToken)) {
            LOG.warn("Google sign-up stopped: token missing or malformed (length {})", idToken.length());
            return redirect("/register?role=" + role).flashing("error", "Google sign-in didn't complete. Please try again.");
        }
        return ok(completePage(request, idToken, role, "", ""));
    }

    /** Create the account: phone number and consent are required by the backend. */
    public CompletionStage<Result> register(Http.Request request) {
        Map<String, String[]> form = request.body().asFormUrlEncoded();
        String role = text(form, "role");
        String idToken = text(form, "idToken");
        String fullName = text(form, "fullName");
        String phone = text(form, "phone").replaceAll("[\\s-]", "");
        boolean terms = "true".equals(text(form, "terms"));

        if (!ROLES.contains(role)) return done(redirect("/get-started"));
        if (!plausibleToken(idToken)) {
            return done(redirect("/register?role=" + role).flashing("error", "Your Google sign-in expired. Please try again."));
        }

        String problem = null;
        if (!fullName.isEmpty() && !fullName.matches("^[\\p{L}][\\p{L} .'-]{1,148}$")) {
            problem = "Enter your name using letters only.";
        } else if (!phone.matches("^(?:\\+254|254|0)[17]\\d{8}$")) {
            problem = "Enter a Kenyan number, like 0712 345 678.";
        } else if (!terms) {
            problem = "Accept the Terms of Service and Privacy Policy to continue.";
        }
        if (problem != null) {
            return done(ok(completePage(request, idToken, role, fullName, problem)));
        }

        ObjectNode body = Json.newObject();
        body.put("idToken", idToken);
        body.put("fullName", fullName);
        body.put("phoneNumber", phone);
        body.put("role", role);
        body.put("termsAndPrivacyAccepted", true);

        final String enteredName = fullName;
        return api.post(request, "/api/v1/auth/google/register", body).thenApply(result -> {
            LOG.info("Google register: backend answered status={} code={} message={}",
                    result.status(), result.code(), result.isSuccess() ? "" : result.errorMessage());
            if (!result.isSuccess()) {
                return ok(completePage(request, idToken, role, enteredName, result.errorMessage()));
            }
            return startCode(request, result, "register");
        });
    }

    // ---------- Helpers ----------

    private play.twirl.api.Html completePage(Http.Request request, String idToken, String role, String typedName, String error) {
        String[] claims = displayClaims(idToken);
        String name = typedName.isEmpty() ? claims[0] : typedName;
        return views.html.googleComplete.render(idToken, role, label(role), name, claims[1], error, request);
    }

    /** Puts the email-code step in the session and sends the person to it. */
    private Result startCode(Http.Request request, ApiResult result, String kind) {
        String challengeId = result.text("challengeId");
        LOG.info("Google {}: challengeId present={}", kind, !challengeId.isEmpty());
        if (challengeId.isEmpty()) {
            return redirect("/get-started?mode=login")
                    .flashing("error", "We couldn't start email verification. Please try again.");
        }
        String masked = result.text("maskedEmail");
        Map<String, String> session = new HashMap<>(request.session().data());
        session.put("otpKind", kind);
        session.put("challengeId", challengeId);
        session.put("maskedEmail", masked.isEmpty() ? "your email" : masked);
        return redirect("/verify").withSession(session);
    }

    private Result backToLogin(String role, String message) {
        return redirect("/login?role=" + role).flashing("error", message);
    }

    /** Name and email for display only. The signature is checked by the backend, never here. */
    private static String[] displayClaims(String idToken) {
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length != 3) return new String[]{"", ""};
            JsonNode payload = Json.parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            return new String[]{payload.path("name").asText(""), payload.path("email").asText("")};
        } catch (RuntimeException e) {
            return new String[]{"", ""};
        }
    }

    private static boolean plausibleToken(String token) {
        return !token.isEmpty() && token.length() <= 8192 && token.split("\\.").length == 3;
    }

    private static String label(String role) {
        return role.charAt(0) + role.substring(1).toLowerCase();
    }

    private static CompletionStage<Result> done(Result result) {
        return CompletableFuture.completedFuture(result);
    }

    private static String text(Map<String, String[]> form, String key) {
        if (form == null) return "";
        String[] values = form.get(key);
        return (values == null || values.length == 0 || values[0] == null) ? "" : values[0].trim();
    }
}
