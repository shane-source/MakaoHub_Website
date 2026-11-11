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
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class PlansController extends Controller {

    private static final Set<String> OWNERS = Set.of("LANDLORD", "SUBLESSOR");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
            .withZone(ZoneId.of("Africa/Nairobi"));

    /** A plan or boost you can buy. */
    public record Offer(String name, String duration, String limit, String price) {}

    /** A plan or boost you already have. */
    public record Grant(String name, String listing, String expires) {}

    /** How one listing is performing. */
    public record Insight(String title, long opens, long enquiries) {}

    public record PlansView(
            String activeLimit,
            String starterLimit,
            List<Offer> offers,
            List<Grant> grants,
            List<Insight> insights,
            boolean showInsights,
            boolean loadFailed) {}

    private final AuthedApi api;

    @Inject
    public PlansController(AuthedApi api) {
        this.api = api;
    }

    public CompletionStage<Result> index(Http.Request request) {
        boolean signedIn = request.session().get("accessToken").isPresent();
        String role = request.session().get("role").orElse("");
        if (!signedIn) {
            return CompletableFuture.completedFuture(redirect("/get-started?mode=login"));
        }
        if (!OWNERS.contains(role)) {
            return CompletableFuture.completedFuture(redirect("/dashboard"));
        }

        boolean landlord = role.equals("LANDLORD");
        String owner = "/api/v1/" + role.toLowerCase(Locale.ROOT);

        // Insights exist for landlords only. The listing titles let us name each boost and insight.
        List<String> paths = landlord
                ? List.of(owner + "/offers", owner + "/offers/insights", owner + "/listings")
                : List.of(owner + "/offers", owner + "/listings");

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) {
                return redirect("/get-started?mode=login").withNewSession();
            }

            JsonNode offersJson = batch.json.get(0);
            JsonNode insightsJson = landlord ? batch.json.get(1) : null;
            JsonNode listingsJson = batch.json.get(landlord ? 2 : 1);

            Map<String, String> titles = new HashMap<>();
            for (JsonNode listing : listOf(listingsJson)) {
                titles.put(listing.path("id").asText(""), listing.path("title").asText(""));
            }

            List<Offer> offers = new ArrayList<>();
            List<Grant> grants = new ArrayList<>();
            String activeLimit = "-";
            String starterLimit = "-";

            if (offersJson != null) {
                activeLimit = String.valueOf(offersJson.path("activeListingLimit").asInt());
                starterLimit = String.valueOf(offersJson.path("starterLimit").asInt());

                for (JsonNode o : offersJson.path("offers")) {
                    offers.add(new Offer(
                            pretty(o.path("code").asText("")),
                            days(o.path("days").asLong()),
                            limit(o.path("listingLimit").asInt()),
                            price(o.path("amountMinor").asLong())));
                }
                for (JsonNode g : offersJson.path("activeGrants")) {
                    String title = titles.get(g.path("listingId").asText(""));
                    grants.add(new Grant(
                            pretty(g.path("code").asText("")),
                            (title == null || title.isEmpty()) ? "All your listings" : title,
                            day(g.path("expiresAt").asText(""))));
                }
            }

            List<Insight> insights = new ArrayList<>();
            for (JsonNode i : listOf(insightsJson)) {
                String title = i.path("title").asText("");
                if (title.isEmpty()) title = titles.getOrDefault(i.path("listingId").asText(""), "Listing");
                insights.add(new Insight(title, i.path("detailOpens").asLong(), i.path("enquiries").asLong()));
            }

            PlansView view = new PlansView(activeLimit, starterLimit, offers, grants, insights, landlord, offersJson == null);
            Result page = ok(views.html.plans.render(role, view, request));

            if (batch.refreshed()) {
                Map<String, String> session = new HashMap<>(request.session().data());
                session.put("accessToken", batch.accessToken);
                session.put("refreshToken", batch.refreshToken);
                return page.withSession(session);
            }
            return page;
        });
    }

    // ---------- Formatting ----------

    /** The backend may return a plain list or an object with a "content" or "items" list. */
    private static List<JsonNode> listOf(JsonNode json) {
        List<JsonNode> out = new ArrayList<>();
        if (json == null) return out;
        JsonNode list = json.isArray() ? json : (json.has("content") ? json.path("content") : json.path("items"));
        if (list.isArray()) list.forEach(out::add);
        return out;
    }

    /** Prices arrive in minor units (cents), so 49900 means KSh 499. */
    private static String price(long minor) {
        long major = minor / 100;
        long cents = minor % 100;
        String text = String.format(Locale.UK, "%,d", major);
        return "KSh " + text + (cents == 0 ? "" : String.format(".%02d", cents));
    }

    private static String days(long days) {
        return days == 1 ? "1 day" : days + " days";
    }

    private static String limit(int count) {
        return count == 1 ? "1 listing" : "Up to " + count + " listings";
    }

    private static String day(String iso) {
        try {
            return DAY.format(Instant.parse(iso));
        } catch (Exception e) {
            return "";
        }
    }

    private static String pretty(String code) {
        if (code.isEmpty()) return "Plan";
        String text = code.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}