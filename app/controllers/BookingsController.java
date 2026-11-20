package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class BookingsController extends Controller {

    private static final Logger LOG = LoggerFactory.getLogger(BookingsController.class);

    private static final Set<String> OWNERS = Set.of("LANDLORD", "SUBLESSOR");
    private static final String LOGIN = "/get-started?mode=login";

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
            .withZone(ZoneId.of("Africa/Nairobi"));

    /** One booking, already worded for the page. The three flags decide which buttons show. */
    public record Row(
            String id,
            String title,
            String person,
            String status,
            String total,
            String breakdown,
            String agreement,
            String moveIn,
            String payout,
            boolean canAgree,
            boolean canMoveIn,
            boolean canRelease) {}

    public record BookingsView(List<Row> rows, boolean loadFailed) {}

    private final AuthedApi api;

    @Inject
    public BookingsController(AuthedApi api) {
        this.api = api;
    }

    // ---------- The list ----------

    public CompletionStage<Result> index(Http.Request request) {
        String role = request.session().get("role").orElse("");
        boolean signedIn = request.session().get("accessToken").isPresent();
        if (!signedIn) {
            return CompletableFuture.completedFuture(redirect(LOGIN));
        }
        if (!OWNERS.contains(role)) {
            return CompletableFuture.completedFuture(redirect("/dashboard"));
        }

        String owner = "/api/v1/" + role.toLowerCase(Locale.ROOT);
        // The listings give each booking a readable title.
        List<String> paths = List.of(owner + "/bookings", owner + "/listings");

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) {
                return redirect(LOGIN).withNewSession();
            }

            JsonNode bookings = batch.json.get(0);
            JsonNode listings = batch.json.get(1);

            Map<String, String> titles = new HashMap<>();
            for (JsonNode listing : listOf(listings)) {
                titles.put(listing.path("id").asText(""), listing.path("title").asText(""));
            }

            List<Row> rows = new ArrayList<>();
            for (JsonNode b : listOf(bookings)) {
                rows.add(row(b, titles));
            }

            Result page = ok(views.html.bookings.render(role, new BookingsView(rows, bookings == null), request));
            if (batch.refreshed()) {
                return page.withSession(sessionWith(request, batch.accessToken, batch.refreshToken));
            }
            return page;
        });
    }

    // ---------- The three buttons ----------

    public CompletionStage<Result> confirmAgreement(Http.Request request, String id) {
        return step(request, id, "agreement/confirm", "agreement");
    }

    public CompletionStage<Result> confirmMoveIn(Http.Request request, String id) {
        return step(request, id, "move-in/confirm", "movein");
    }

    public CompletionStage<Result> requestRelease(Http.Request request, String id) {
        return step(request, id, "release-review-request", "release");
    }

    private CompletionStage<Result> step(Http.Request request, String id, String path, String key) {
        String role = request.session().get("role").orElse("");
        boolean signedIn = request.session().get("accessToken").isPresent();
        if (!signedIn) {
            return CompletableFuture.completedFuture(redirect(LOGIN));
        }
        if (!OWNERS.contains(role)) {
            return CompletableFuture.completedFuture(redirect("/dashboard"));
        }
        // The id goes into a backend address, so only plain ids are accepted.
        if (!isSafeId(id)) {
            return CompletableFuture.completedFuture(redirect("/dashboard/bookings"));
        }

        String address = "/api/v1/" + role.toLowerCase(Locale.ROOT) + "/bookings/" + id + "/" + path;

        // The backend decides whether this person may do this step on this booking.
        return api.post(request, address, null).thenApply(result -> {
            if (result.expired) {
                return redirect(LOGIN).withNewSession();
            }
            if (!result.ok()) {
                LOG.warn("booking step {} failed: status={} body={}", key, result.status, result.json);
            }
            Result back = redirect("/dashboard/bookings").flashing("bk", result.ok() ? key + "-ok" : "failed");
            if (result.refreshed()) {
                return back.withSession(sessionWith(request, result.accessToken, result.refreshToken));
            }
            return back;
        });
    }

    // ---------- One booking ----------

    private static Row row(JsonNode b, Map<String, String> titles) {
        String currency = b.path("currency").asText("");
        String title = titles.get(b.path("listingId").asText(""));
        String id = b.path("id").asText("");

        String breakdown = "Rent " + money(b.path("rentAmountMinor").asLong(), currency)
                + " · Deposit " + money(b.path("depositAmountMinor").asLong(), currency)
                + " · Makao fee " + money(b.path("commissionAmountMinor").asLong(), currency);

        boolean you = has(b, "ownerAgreedAt");
        boolean renter = has(b, "renterAgreedAt");
        boolean reported = has(b, "ownerMoveInConfirmedAt");
        boolean answered = has(b, "moveInAnsweredAt");
        boolean accepted = b.path("moveInAccepted").asBoolean(false);
        boolean requested = has(b, "ownerReleaseRequestedAt");
        boolean paidOut = has(b, "releasedAt");

        // The buttons follow the dates on the booking. The backend still has the final say.
        boolean usable = isSafeId(id);
        boolean canAgree = usable && !you && !paidOut;
        boolean canMoveIn = usable && you && renter && !reported && !paidOut;
        // After a "yes" the automatic payout takes over, so a review is only for "no" or no answer.
        boolean canRelease = usable && reported && !paidOut && !requested && !(answered && accepted);

        return new Row(
                id,
                (title == null || title.isEmpty()) ? "Booking" : title,
                b.path("renterName").asText(""),
                pretty(b.path("status").asText("")),
                money(b.path("totalAmountMinor").asLong(), currency),
                breakdown,
                agreement(you, renter),
                moveInText(b, reported, answered, accepted, requested),
                payout(b),
                canAgree,
                canMoveIn,
                canRelease);
    }

    /** The viewer is the owner, so "ownerAgreedAt" is the viewer's own agreement. */
    private static String agreement(boolean you, boolean renter) {
        if (you && renter) return "Both sides agreed to the booking terms";
        if (you) return "You agreed. Waiting for the renter.";
        if (renter) return "The renter agreed. Waiting for you.";
        return "Booking terms not agreed yet";
    }

    /** Where the move-in stands, including the renter's answer and any review you asked for. */
    private static String moveInText(JsonNode b, boolean reported, boolean answered, boolean accepted, boolean requested) {
        if (!reported) return "";
        String line;
        if (!answered) {
            line = "You reported the move-in. Waiting for the renter's answer.";
        } else if (accepted) {
            line = "The renter confirmed the move-in.";
        } else {
            line = "The renter said they haven't moved in. The payment stays held.";
        }
        if (requested) {
            line += " Release review requested on " + day(b.path("ownerReleaseRequestedAt").asText("")) + ".";
        }
        return line;
    }

    private static String payout(JsonNode b) {
        if (has(b, "releasedAt")) return "Paid out on " + day(b.path("releasedAt").asText(""));
        String sent = b.path("testPayoutStatus").asText("");
        if (!sent.isEmpty() && !"NOT_QUEUED".equals(sent)) return payoutLabel(sent);
        if (has(b, "releaseEligibleAt")) return "Eligible for release from " + day(b.path("releaseEligibleAt").asText(""));
        return "Payout not released yet";
    }

    /** Plain wording for each payout state the backend can report. */
    private static String payoutLabel(String code) {
        return switch (code) {
            case "SUCCEEDED" -> "Payout succeeded: sent to the owner's M-Pesa";
            case "QUEUED" -> "Payout queued";
            case "PENDING" -> "Payout in progress with Paystack";
            case "OTP" -> "Payout waiting for Paystack confirmation";
            case "FAILED" -> "Payout failed";
            case "REVERSED" -> "Payout reversed";
            case "REVIEW", "REVIEW_REQUIRED" -> "Payout under review by our team";
            case "DESTINATION_REQUIRED" -> "Payout waiting for the owner's M-Pesa number";
            case "OWNER_NOT_VERIFIED" -> "Payout waiting for owner verification";
            default -> "Payout: " + pretty(code).toLowerCase(Locale.ROOT);
        };
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
}