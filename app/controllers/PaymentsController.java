package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

/** Payment history for any signed-in role, with a check for payments still waiting. */
public class PaymentsController extends Controller {

    private static final Logger LOG = LoggerFactory.getLogger(PaymentsController.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a", Locale.ENGLISH)
            .withZone(ZoneId.of("Africa/Nairobi"));
    private static final Set<String> FINISHED =
            Set.of("SUCCEEDED", "SUCCESS", "COMPLETED", "PAID", "FAILED", "CANCELLED", "EXPIRED", "REFUNDED");

    public record Row(String what, String amount, String status, String when, String reference, boolean canCheck, String look) {}

    public record PaymentsView(List<Row> rows, int page, boolean hasNext, boolean loaded) {}

    private final AuthedApi api;

    @Inject
    public PaymentsController(AuthedApi api) {
        this.api = api;
    }

    public CompletionStage<Result> index(Http.Request request, Integer pageNumber) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());
        int number = (pageNumber == null || pageNumber < 0) ? 0 : Math.min(pageNumber, 500);

        return api.getAll(request, List.of("/api/v1/payments?page=" + number + "&size=20")).thenApply(batch -> {
            if (batch.expired) return DashSupport.toSignIn();

            JsonNode json = batch.json.get(0);
            List<Row> rows = new ArrayList<>();
            boolean hasNext = false;
            if (json != null) {
                JsonNode list = json.isArray() ? json : (json.has("content") ? json.path("content") : json.path("items"));
                if (list.isArray()) {
                    for (JsonNode p : list) rows.add(row(p));
                }
                hasNext = json.path("hasNext").asBoolean(false)
                        || (json.has("last") && !json.path("last").asBoolean(true));
            }

            Result page = ok(views.html.payments.render(role, new PaymentsView(rows, number, hasNext, json != null), request));
            return DashSupport.applyTokens(page, batch, request);
        });
    }

    /** Asks the backend to look up the payment with Paystack and update it. */
    public CompletionStage<Result> check(Http.Request request) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());

        Map<String, String[]> form = request.body().asFormUrlEncoded();
        String reference = (form == null || !form.containsKey("reference") || form.get("reference").length == 0)
                ? "" : form.get("reference")[0].trim();
        if (!reference.matches("[A-Za-z0-9_.=-]{1,100}")) {
            return DashSupport.done(redirect("/dashboard/payments"));
        }

        ObjectNode body = Json.newObject();
        body.put("reference", reference);

        return api.post(request, "/api/v1/payments/paystack/reconcile", body).thenApply(result -> {
            if (result.expired) return DashSupport.toSignIn();
            if (!result.ok()) {
                LOG.warn("payment check failed: status={} body={}", result.status, result.json);
            }
            Result back = redirect("/dashboard/payments").flashing("pay", result.ok() ? "checked" : "failed");
            if (result.refreshed()) {
                Map<String, String> session = new java.util.HashMap<>(request.session().data());
                session.put("accessToken", result.accessToken);
                session.put("refreshToken", result.refreshToken);
                return back.withSession(session);
            }
            return back;
        });
    }

    private static Row row(JsonNode p) {
        String status = p.path("status").asText("");
        String reference = p.path("providerReference").asText("");
        boolean safeRef = reference.matches("[A-Za-z0-9_.=-]{1,100}");
        return new Row(
                what(p.path("paymentType").asText("")),
                money(p.path("amountMinor").asLong(), p.path("currency").asText("")),
                DashSupport.pretty(status),
                when(p.path("completedAt").asText("").isEmpty() ? p.path("createdAt").asText("") : p.path("completedAt").asText("")),
                safeRef ? reference : "",
                safeRef && !FINISHED.contains(status),
                DashSupport.statusClass(status));
    }

    private static String what(String type) {
        switch (type) {
            case "BOOKING": return "Booking payment";
            case "LOCATION_UNLOCK": return "Location unlock";
            case "PHONE_UNLOCK": return "Phone number unlock";
            case "VIDEO_UNLOCK": return "Video unlock";
            default: return type.isEmpty() ? "Payment" : DashSupport.pretty(type);
        }
    }

    private static String money(long minor, String currency) {
        String prefix = (currency.isEmpty() || currency.equals("KES")) ? "KSh " : currency + " ";
        long cents = minor % 100;
        return prefix + String.format(Locale.UK, "%,d", minor / 100) + (cents == 0 ? "" : String.format(".%02d", cents));
    }

    private static String when(String iso) {
        try {
            return DAY.format(OffsetDateTime.parse(iso).toInstant());
        } catch (Exception e) {
            return "";
        }
    }
}
