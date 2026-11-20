package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class PayoutController extends Controller {

    private static final Logger LOG = LoggerFactory.getLogger(PayoutController.class);

    private static final Set<String> OWNERS = Set.of("LANDLORD", "SUBLESSOR");
    private static final String LOGIN = "/get-started?mode=login";
    private static final String PAGE = "/dashboard/payout-number";

    // The same rule as the Android app: 07XXXXXXXX or 01XXXXXXXX.
    private static final Pattern PHONE = Pattern.compile("0[17][0-9]{8}");

    public record PayoutView(boolean configured, boolean eligible, boolean verified, boolean loadFailed) {}

    private final AuthedApi api;

    @Inject
    public PayoutController(AuthedApi api) {
        this.api = api;
    }

    // ---------- The page ----------

    public CompletionStage<Result> index(Http.Request request) {
        String role = request.session().get("role").orElse("");
        boolean signedIn = request.session().get("accessToken").isPresent();
        if (!signedIn) return completed(redirect(LOGIN));
        if (!OWNERS.contains(role)) return completed(redirect("/dashboard"));

        String owner = "/api/v1/" + role.toLowerCase(Locale.ROOT);
        List<String> paths = List.of(owner + "/payments/test-destination", owner + "/verification");

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) return redirect(LOGIN).withNewSession();

            JsonNode status = batch.json.get(0);
            JsonNode verification = batch.json.get(1);

            PayoutView view = new PayoutView(
                    status != null && status.path("configured").asBoolean(false),
                    status != null && status.path("eligible").asBoolean(false),
                    verification != null && "VERIFIED".equals(verification.path("status").asText("")),
                    status == null);

            Result page = ok(views.html.payout.render(role, view, request));
            if (batch.refreshed()) {
                return page.withSession(sessionWith(request, batch.accessToken, batch.refreshToken));
            }
            return page;
        });
    }

    // ---------- Saving the number ----------

    public CompletionStage<Result> save(Http.Request request) {
        String role = request.session().get("role").orElse("");
        boolean signedIn = request.session().get("accessToken").isPresent();
        if (!signedIn) return completed(redirect(LOGIN));
        if (!OWNERS.contains(role)) return completed(redirect("/dashboard"));

        String phone = field(request, "phone").replaceAll("[\\s-]", "");
        if (!PHONE.matcher(phone).matches()) {
            return completed(redirect(PAGE).flashing("payout", "format"));
        }
        if (!"yes".equals(field(request, "own"))) {
            return completed(redirect(PAGE).flashing("payout", "own"));
        }

        ObjectNode body = Json.newObject();
        body.put("phone", phone);
        body.put("ownNumberConfirmed", true);

        String address = "/api/v1/" + role.toLowerCase(Locale.ROOT) + "/payments/test-destination";

        // The backend makes the final decision, for example whether verification is complete.
        return api.post(request, address, body).thenApply(result -> {
            if (result.expired) return redirect(LOGIN).withNewSession();
            if (!result.ok()) {
                // The number itself is never logged.
                LOG.warn("payout number not saved: status={} body={}", result.status, result.json);
            }
            Result back = redirect(PAGE).flashing("payout", result.ok() ? "saved" : "failed");
            if (result.refreshed()) {
                return back.withSession(sessionWith(request, result.accessToken, result.refreshToken));
            }
            return back;
        });
    }

    // ---------- Helpers ----------

    private static String field(Http.Request request, String name) {
        Map<String, String[]> form = request.body().asFormUrlEncoded();
        if (form == null || !form.containsKey(name) || form.get(name).length == 0) return "";
        return form.get(name)[0].trim();
    }

    private static Map<String, String> sessionWith(Http.Request request, String access, String refresh) {
        Map<String, String> session = new HashMap<>(request.session().data());
        session.put("accessToken", access);
        session.put("refreshToken", refresh);
        return session;
    }

    private static CompletionStage<Result> completed(Result result) {
        return CompletableFuture.completedFuture(result);
    }
}