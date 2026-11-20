package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class RenterBookingsController extends Controller {

    private static final Logger LOG = LoggerFactory.getLogger(RenterBookingsController.class);

    private static final String LOGIN = "/get-started?mode=login";
    private static final String PAGE = "/dashboard/my-bookings";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
            .withZone(ZoneId.of("Africa/Nairobi"));

    /** One booking, already worded for the page. The two flags decide which buttons show. */
    public record Row(
            String id,
            String title,
            String owner,
            String status,
            String total,
            String breakdown,
            String agreement,
            String moveIn,
            String payout,
            boolean canAgree,
            boolean canAnswer,
            String rent,
            String deposit,
            String fee,
            boolean paid,
            boolean ownerConfirmed,
            boolean youConfirmed,
            boolean released,
            String state) {}

    public record BookingsView(List<Row> rows, boolean loadFailed) {}

    private final AuthedApi api;

    @Inject
    public RenterBookingsController(AuthedApi api) {
        this.api = api;
    }

    // ---------- The list ----------

    public CompletionStage<Result> index(Http.Request request) {
        String role = request.session().get("role").orElse("");
        boolean signedIn = request.session().get("accessToken").isPresent();
        if (!signedIn) return completed(redirect(LOGIN));
        if (!role.equals("RENTER")) return completed(redirect("/dashboard"));

        return api.getAll(request, List.of("/api/v1/renter/bookings")).thenApply(batch -> {
            if (batch.expired) return redirect(LOGIN).withNewSession();

            JsonNode bookings = batch.json.get(0);
            List<Row> rows = new ArrayList<>();
            for (JsonNode b : listOf(bookings)) {
                rows.add(row(b));
            }

            Result page = ok(views.html.renterBookings.render(role, new BookingsView(rows, bookings == null), request));
            if (batch.refreshed()) {
                return page.withSession(sessionWith(request, batch.accessToken, batch.refreshToken));
            }
            return page;
        });
    }

    // ---------- The two actions ----------

    /** Confirm the booking terms. */
    public CompletionStage<Result> agree(Http.Request request, String id) {
        return act(request, id, "agreement/confirm", null, "agreement-ok");
    }

    /** Answer whether the move-in the owner reported really happened. */
    public CompletionStage<Result> moveIn(Http.Request request, String id) {
        String answer = field(request, "answer");
        if (!answer.equals("yes") && !answer.equals("no")) {
            return completed(redirect(PAGE));
        }
        ObjectNode body = Json.newObject();
        body.put("accepted", answer.equals("yes"));
        return act(request, id, "move-in/response", body, answer.equals("yes") ? "movein-yes" : "movein-no");
    }

    private CompletionStage<Result> act(Http.Request request, String id, String path, ObjectNode body, String okKey) {
        String role = request.session().get("role").orElse("");
        boolean signedIn = request.session().get("accessToken").isPresent();
        if (!signedIn) return completed(redirect(LOGIN));
        if (!role.equals("RENTER")) return completed(redirect("/dashboard"));
        // The id goes into a backend address, so only plain ids are accepted.
        if (!isSafeId(id)) return completed(redirect(PAGE));

        String address = "/api/v1/renter/bookings/" + id + "/" + path;

        // The backend decides whether this step is allowed on this booking.
        return api.post(request, address, body).thenApply(result -> {
            if (result.expired) return redirect(LOGIN).withNewSession();
            if (!result.ok()) {
                LOG.warn("renter booking step {} failed: status={} body={}", path, result.status, result.json);
            }
            Result back = redirect(PAGE).flashing("rb", result.ok() ? okKey : "failed");
            if (result.refreshed()) {
                return back.withSession(sessionWith(request, result.accessToken, result.refreshToken));
            }
            return back;
        });
    }

    // ---------- One booking ----------

    private static Row row(JsonNode b) {
        String currency = b.path("currency").asText("");
        String id = b.path("id").asText("");
        String owner = b.path("ownerName").asText("");
        String kind = b.path("listingKind").asText("");

        String breakdown = "Rent " + money(b.path("rentAmountMinor").asLong(), currency)
                + " · Deposit " + money(b.path("depositAmountMinor").asLong(), currency)
                + " · Makao fee " + money(b.path("commissionAmountMinor").asLong(), currency);

        boolean you = has(b, "renterAgreedAt");
        boolean theyAgreed = has(b, "ownerAgreedAt");
        boolean reported = has(b, "ownerMoveInConfirmedAt");
        boolean answered = has(b, "moveInAnsweredAt");
        boolean released = has(b, "releasedAt");

        boolean usable = isSafeId(id);
        String rawStatus = b.path("status").asText("");
        boolean paid = !(rawStatus.isEmpty() || rawStatus.equals("AWAITING_PAYMENT") || rawStatus.equals("PAYMENT_PENDING")
                || rawStatus.equals("CREATED") || rawStatus.equals("EXPIRED") || rawStatus.equals("CANCELLED"));

        return new Row(
                id,
                owner.isEmpty() ? "Booking" : "Booking with " + owner,
                kind.isEmpty() ? "" : pretty(kind),
                pretty(b.path("status").asText("")),
                money(b.path("totalAmountMinor").asLong(), currency),
                breakdown,
                agreement(you, theyAgreed),
                moveInText(b, you && theyAgreed, reported, answered),
                payout(b),
                usable && !you && !released,
                usable && reported && !answered && !released,
                money(b.path("rentAmountMinor").asLong(), currency),
                money(b.path("depositAmountMinor").asLong(), currency),
                money(b.path("commissionAmountMinor").asLong(), currency),
                paid,
                reported,
                answered && b.path("moveInAccepted").asBoolean(false),
                released,
                released ? "Released" : "Held");
    }

    private static String agreement(boolean you, boolean owner) {
        if (you && owner) return "Both sides agreed to the booking terms";
        if (you) return "You agreed. Waiting for the owner.";
        if (owner) return "The owner agreed. Waiting for you.";
        return "Booking terms not agreed yet";
    }

    private static String moveInText(JsonNode b, boolean bothAgreed, boolean reported, boolean answered) {
        if (answered) {
            return b.path("moveInAccepted").asBoolean(false)
                    ? "You confirmed that you moved in."
                    : "You said you haven't moved in. The payment stays held.";
        }
        if (reported) return "The owner reported that you moved in. Please answer below.";
        if (bothAgreed) return "Waiting for the owner to report your move-in.";
        return "";
    }

    private static String payout(JsonNode b) {
        if (has(b, "releasedAt")) return "Payment released to the owner on " + day(b.path("releasedAt").asText(""));
        if (has(b, "releaseEligibleAt")) return "Payment can be released from " + day(b.path("releaseEligibleAt").asText(""));
        return "Your payment is held until move-in is confirmed.";
    }

    // ---------- Helpers ----------

    private static boolean has(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return !value.isMissingNode() && !value.isNull() && !value.asText("").isEmpty();
    }

    private static boolean isSafeId(String id) {
        return id != null && id.matches("[A-Za-z0-9-]{1,64}");
    }

    /** The backend may return a plain list or an object with a "content" or "items" list. */
    private static List<JsonNode> listOf(JsonNode json) {
        List<JsonNode> out = new ArrayList<>();
        if (json == null) return out;
        JsonNode list = json.isArray() ? json : (json.has("content") ? json.path("content") : json.path("items"));
        if (list.isArray()) list.forEach(out::add);
        return out;
    }

    private static String field(Http.Request request, String name) {
        Map<String, String[]> form = request.body().asFormUrlEncoded();
        if (form == null || !form.containsKey(name) || form.get(name).length == 0) return "";
        return form.get(name)[0].trim();
    }

    /** Amounts arrive in minor units (cents), so 4500000 means KSh 45,000. */
    private static String money(long minor, String currency) {
        String prefix = (currency.isEmpty() || currency.equals("KES")) ? "KSh " : currency + " ";
        long major = minor / 100;
        long cents = minor % 100;
        return prefix + String.format(Locale.UK, "%,d", major) + (cents == 0 ? "" : String.format(".%02d", cents));
    }

    private static String day(String iso) {
        try {
            return DAY.format(Instant.parse(iso));
        } catch (Exception e) {
            return "";
        }
    }

    private static String pretty(String code) {
        if (code.isEmpty()) return "";
        String text = code.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
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