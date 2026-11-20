package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.MakaoApi;

public class AuthController extends Controller {

    private static final Set<String> ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");

    private final MakaoApi api;
    private final String googleClientId;

    @Inject
    public AuthController(MakaoApi api, Config config) {
        this.api = api;
        this.googleClientId = config.hasPath("makao.google.clientId") ? config.getString("makao.google.clientId") : "";
    }

    // ---------- Pages ----------

    // Choose a role. mode is "register" (default) or "login".
    public Result rolePage(Http.Request request, String mode, String change) {
        // A returning user goes straight to sign-in as their last role. "change" lets them pick another role.
        String last = DashSupport.lastRole(request);
        if ("login".equals(mode) && last != null && !"1".equals(change)) {
            return redirect("/login?role=" + last);
        }
        return ok(views.html.role.render("login".equals(mode) ? "login" : "register"));
    }

    public Result registerPage(Http.Request request, String role) {
        String selected = ROLES.contains(role) ? role : "RENTER";
        return ok(views.html.register.render(
                selected,
                label(selected),
                flashValue(request, "error"),
                flashValue(request, "fullName"),
                flashValue(request, "email"),
                flashValue(request, "phone"),
                googleClientId,
                request));
    }

    public Result loginPage(Http.Request request, String role) {
        if (!ROLES.contains(role)) {
            return redirect("/get-started?mode=login");
        }
        return ok(views.html.login.render(
                role,
                label(role),
                flashValue(request, "error"),
                flashValue(request, "email"),
                googleClientId,
                request));
    }

    public Result verifyPage(Http.Request request) {
        if (request.session().get("challengeId").isEmpty()) {
            return redirect("/get-started");
        }
        String masked = request.session().get("maskedEmail").orElse("your email");
        return ok(views.html.verify.render(masked, flashValue(request, "error"), request));
    }

    // ---------- Register ----------

    public CompletionStage<Result> register(Http.Request request) {
        Map<String, String[]> form = request.body().asFormUrlEncoded();

        String role = text(form, "role");
        String fullName = text(form, "fullName");
        String email = text(form, "email");
        String phone = text(form, "phone").replaceAll("[\\s-]", "");
        String password = raw(form, "password");
        String confirm = raw(form, "confirmPassword");
        boolean terms = "true".equals(raw(form, "terms"));

        if (!ROLES.contains(role)) {
            return done(redirect("/get-started"));
        }

        // The browser already checked these. We check again because anyone can bypass the browser.
        String problem = registrationProblem(fullName, email, phone, password);
        if (problem == null && !password.equals(confirm)) {
            problem = "Passwords do not match.";
        } else if (problem == null && !terms) {
            problem = "Accept the Terms of Service and Privacy Policy to continue.";
        }
        if (problem != null) {
            return done(backToRegister(role, problem, fullName, email, phone));
        }

        ObjectNode body = Json.newObject();
        body.put("fullName", fullName);
        body.put("email", email);
        body.put("phoneNumber", phone);
        body.put("password", password);
        body.put("role", role);
        body.put("termsAndPrivacyAccepted", true);

        return api.post(request, "/api/v1/auth/register", body).thenApply(result -> {
            if (!result.isSuccess()) {
                return backToRegister(role, result.errorMessage(), fullName, email, phone);
            }
            String challengeId = result.text("challengeId");
            if (challengeId.isEmpty()) {
                return backToRegister(role,
                        "We couldn't start email verification. Please try again.",
                        fullName, email, phone);
            }
            String masked = result.text("maskedEmail");

            Map<String, String> session = new HashMap<>(request.session().data());
            session.put("otpKind", "register");
            session.put("challengeId", challengeId);
            session.put("maskedEmail", masked.isEmpty() ? email : masked);
            return redirect("/verify").withSession(session);
        });
    }

    // ---------- Sign in (the chosen role is sent as expectedRole) ----------

    public CompletionStage<Result> login(Http.Request request) {
        Map<String, String[]> form = request.body().asFormUrlEncoded();

        String role = text(form, "role");
        String email = text(form, "email");
        String password = raw(form, "password");

        if (!ROLES.contains(role)) {
            return done(redirect("/get-started?mode=login"));
        }
        if (email.isEmpty() || password.isEmpty()) {
            return done(backToLogin(role, "Enter your email and password.", email));
        }
        if (!validEmail(email)) {
            return done(backToLogin(role, "Enter a valid email address.", email));
        }

        ObjectNode body = Json.newObject();
        body.put("email", email);
        body.put("password", password);
        body.put("expectedRole", role);

        return api.post(request, "/api/v1/auth/login", body).thenApply(result -> {
            if (!result.isSuccess()) {
                return backToLogin(role, result.errorMessage(), email);
            }
            // An active account is signed in straight away: no email code on sign-in.
            if (!result.text("accessToken").isEmpty()) {
                return signedIn(request, result);
            }
            String challengeId = result.text("challengeId");
            if (challengeId.isEmpty()) {
                return backToLogin(role, "We couldn't start email verification. Please try again.", email);
            }
            String masked = result.text("maskedEmail");

            Map<String, String> session = new HashMap<>(request.session().data());
            session.put("otpKind", "login");
            session.put("challengeId", challengeId);
            session.put("maskedEmail", masked.isEmpty() ? email : masked);
            return redirect("/verify").withSession(session);
        });
    }

    // ---------- Verify the 6-digit code ----------

    public CompletionStage<Result> verify(Http.Request request) {
        String challengeId = request.session().get("challengeId").orElse("");
        String kind = request.session().get("otpKind").orElse("register");
        if (challengeId.isEmpty()) {
            return done(redirect("/get-started"));
        }

        String code = text(request.body().asFormUrlEncoded(), "code");

        ObjectNode body = Json.newObject();
        body.put("challengeId", challengeId);
        body.put("code", code);

        String path;
        switch (kind) {
            case "login": path = "/api/v1/auth/login/verify-otp"; break;
            case "googleLogin": path = "/api/v1/auth/google/verify-otp"; break;
            case "reactivation": path = "/api/v1/auth/reactivate/verify-otp"; break;
            default: path = "/api/v1/auth/register/verify-otp";
        }

        return api.post(request, path, body).thenApply(result -> {
            if (!result.isSuccess()) {
                return redirect("/verify").flashing("error", result.errorMessage());
            }
            return signedIn(request, result);
        });
    }

    private Result signedIn(Http.Request request, services.ApiResult result) {
        Map<String, String> session = new HashMap<>(request.session().data());
        session.remove("challengeId");
        session.remove("otpKind");
        session.remove("maskedEmail");
        session.put("accessToken", result.text("accessToken"));
        session.put("refreshToken", result.text("refreshToken"));
        session.put("fullName", result.text("fullName"));
        session.put("role", result.text("role"));
        return DashSupport.rememberRole(redirect("/dashboard").withSession(session), result.text("role"));
    }

    // ---------- Forgot password ----------

    public Result forgotPage(Http.Request request) {
        return ok(views.html.forgot.render(
                flashValue(request, "error"),
                flashValue(request, "email"),
                request));
    }

    public CompletionStage<Result> forgot(Http.Request request) {
        String email = text(request.body().asFormUrlEncoded(), "email");
        if (email.isEmpty()) {
            return done(backToForgot("Enter your email address.", email));
        }

        ObjectNode body = Json.newObject();
        body.put("email", email);

        return api.post(request, "/api/v1/auth/forgot-password", body).thenApply(result -> {
            if (!result.isSuccess()) {
                return backToForgot(result.errorMessage(), email);
            }
            // The same answer whether or not the email has an account.
            Map<String, String> session = new HashMap<>(request.session().data());
            session.put("resetEmail", email);
            return redirect("/reset").withSession(session)
                    .flashing("notice", "If an account exists for that email, we sent a 6-digit code to it.");
        });
    }

    public Result resetPage(Http.Request request) {
        String email = request.session().get("resetEmail").orElse("");
        if (email.isEmpty()) {
            return redirect("/forgot");
        }
        return ok(views.html.reset.render(
                email,
                flashValue(request, "error"),
                flashValue(request, "notice"),
                request));
    }

    public CompletionStage<Result> reset(Http.Request request) {
        String email = request.session().get("resetEmail").orElse("");
        if (email.isEmpty()) {
            return done(redirect("/forgot"));
        }

        Map<String, String[]> form = request.body().asFormUrlEncoded();
        String code = text(form, "code");
        String password = raw(form, "password");
        String confirm = raw(form, "confirmPassword");

        String problem = null;
        if (!code.matches("\\d{6}")) {
            problem = "Enter the 6-digit code from your email.";
        } else if (password.length() < 8 || password.length() > 72
                || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            problem = "Use 8 to 72 characters, with a letter and a number.";
        } else if (!password.equals(confirm)) {
            problem = "Passwords do not match.";
        }
        if (problem != null) {
            return done(redirect("/reset").flashing("error", problem));
        }

        ObjectNode body = Json.newObject();
        body.put("email", email);
        body.put("code", code);
        body.put("newPassword", password);

        return api.post(request, "/api/v1/auth/forgot-password/reset", body).thenApply(result -> {
            if (!result.isSuccess()) {
                return redirect("/reset").flashing("error", result.errorMessage());
            }
            Map<String, String> session = new HashMap<>(request.session().data());
            session.remove("resetEmail");
            return redirect("/password-updated").withSession(session);
        });
    }

    public Result passwordUpdatedPage() {
        return ok(views.html.passwordUpdated.render());
    }

    // ---------- Helpers ----------

    private Result backToRegister(String role, String message, String fullName, String email, String phone) {
        Map<String, String> flash = new HashMap<>();
        flash.put("error", message);
        flash.put("fullName", fullName);
        flash.put("email", email);
        flash.put("phone", phone);
        return redirect("/register?role=" + role).flashing(flash);
    }

    private Result backToLogin(String role, String message, String email) {
        Map<String, String> flash = new HashMap<>();
        flash.put("error", message);
        flash.put("email", email);
        return redirect("/login?role=" + role).flashing(flash);
    }

    private Result backToForgot(String message, String email) {
        Map<String, String> flash = new HashMap<>();
        flash.put("error", message);
        flash.put("email", email);
        return redirect("/forgot").flashing(flash);
    }

    // ---------- Checks shared by the forms (the browser checks first; these cannot be bypassed) ----------

    private static boolean validEmail(String email) {
        return email.length() <= 320 && email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$");
    }

    /** The first thing wrong with a new account's details, or null when they are fine. */
    private static String registrationProblem(String fullName, String email, String phone, String password) {
        if (!fullName.matches("^[\\p{L}][\\p{L} .'-]{1,78}$") || fullName.trim().split("\\s+").length < 2) {
            return "Enter your first and last name.";
        }
        if (!validEmail(email)) return "Enter a valid email address.";
        if (!phone.matches("^(?:\\+254|254|0)[17]\\d{8}$")) return "Enter a Kenyan number, like 0712 345 678.";
        if (password.length() < 8 || password.length() > 72
                || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            return "Use 8 to 72 characters for your password, with a letter and a number.";
        }
        return null;
    }

    private static String label(String role) {
        return role.charAt(0) + role.substring(1).toLowerCase();
    }

    private static CompletionStage<Result> done(Result result) {
        return CompletableFuture.completedFuture(result);
    }

    private static String flashValue(Http.Request request, String key) {
        return request.flash().get(key).orElse("");
    }

    // A form field exactly as typed (passwords must not be trimmed).
    private static String raw(Map<String, String[]> form, String key) {
        if (form == null) return "";
        String[] values = form.get(key);
        return (values == null || values.length == 0 || values[0] == null) ? "" : values[0];
    }

    // A form field with surrounding spaces removed.
    private static String text(Map<String, String[]> form, String key) {
        return raw(form, key).trim();
    }
}