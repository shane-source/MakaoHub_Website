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
    public record Offer(String code, String name, String duration, String limit, String photos, String boosts, String price) {}

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
            boolean loadFailed,
            int boostCredits,
            String planName) {}

    /** The boost page for one listing: a free included boost and the boosts you can buy. */
    public record BoostView(String listingId, String title, int credits, List<Offer> offers, boolean loadFailed) {}

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
                    if (isBoost(o.path("code").asText(""))) continue;
                    offers.add(toOffer(o));
                }
                for (JsonNode g : offersJson.path("activeGrants")) {
                    String title = titles.get(g.path("listingId").asText(""));
                    grants.add(new Grant(
                            label(g.path("code").asText("")),
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

            int credits = offersJson == null ? 0 : offersJson.path("boostCredits").asInt(0);
            String planName = "Free";
            if (offersJson != null) {
                for (JsonNode g : offersJson.path("activeGrants")) {
                    String code = g.path("code").asText("");
                    if (code.contains("PRO_MAX")) planName = "Pro Max";
                    else if (code.contains("PRO") && !planName.equals("Pro Max")) planName = "Pro";
                }
            }
            PlansView view = new PlansView(activeLimit, starterLimit, offers, grants, insights, landlord, offersJson == null, credits, planName);
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

    /** Uses one boost credit on a listing, like the Boost button in the app. */
    public CompletionStage<Result> boost(Http.Request request, String id) {
        String role = request.session().get("role").orElse("");
        if (request.session().get("accessToken").isEmpty()) {
            return CompletableFuture.completedFuture(redirect("/get-started?mode=login"));
        }
        if (!OWNERS.contains(role)) {
            return CompletableFuture.completedFuture(redirect("/dashboard"));
        }
        if (!id.matches("[A-Za-z0-9-]{1,64}")) {
            return CompletableFuture.completedFuture(redirect("/dashboard/listings"));
        }

        com.fasterxml.jackson.databind.node.ObjectNode body = play.libs.Json.newObject();
        body.put("listingId", id);

        String path = "/api/v1/" + role.toLowerCase(Locale.ROOT) + "/offers/boost-credit/redeem";
        return api.post(request, path, body).thenApply(result -> {
            if (result.expired) {
                return redirect("/get-started?mode=login").withNewSession();
            }
            String flash = result.ok() ? "boosted" : (result.status == 409 || result.status == 402 ? "no-credit" : "boost-failed");
            Result next = redirect("/dashboard/listings").flashing("ls", flash);
            if (result.refreshed()) {
                Map<String, String> session = new HashMap<>(request.session().data());
                session.put("accessToken", result.accessToken);
                session.put("refreshToken", result.refreshToken);
                return next.withSession(session);
            }
            return next;
        });
    }

    /** The boost page for one listing, like the app's Boost screen. */
    public CompletionStage<Result> boostPage(Http.Request request, String id) {
        String role = request.session().get("role").orElse("");
        if (request.session().get("accessToken").isEmpty()) {
            return CompletableFuture.completedFuture(redirect("/get-started?mode=login"));
        }
        if (!OWNERS.contains(role)) {
            return CompletableFuture.completedFuture(redirect("/dashboard"));
        }
        if (!id.matches("[A-Za-z0-9-]{1,64}")) {
            return CompletableFuture.completedFuture(redirect("/dashboard/listings"));
        }
        String owner = "/api/v1/" + role.toLowerCase(Locale.ROOT);
        return api.getAll(request, List.of(owner + "/offers", owner + "/listings")).thenApply(batch -> {
            if (batch.expired) {
                return redirect("/get-started?mode=login").withNewSession();
            }
            JsonNode offersJson = batch.json.get(0);
            String title = "";
            for (JsonNode listing : listOf(batch.json.get(1))) {
                if (listing.path("id").asText("").equals(id)) title = listing.path("title").asText("");
            }
            List<Offer> boosts = new ArrayList<>();
            if (offersJson != null) {
                for (JsonNode o : offersJson.path("offers")) {
                    if (isBoost(o.path("code").asText(""))) boosts.add(toOffer(o));
                }
            }
            int credits = offersJson == null ? 0 : offersJson.path("boostCredits").asInt(0);
            Result page = ok(views.html.boost.render(role, new BoostView(id, title, credits, boosts, offersJson == null), request));
            if (batch.refreshed()) {
                Map<String, String> session = new HashMap<>(request.session().data());
                session.put("accessToken", batch.accessToken);
                session.put("refreshToken", batch.refreshToken);
                return page.withSession(session);
            }
            return page;
        });
    }

    /** Starts the payment for a plan. The owner finishes it on Paystack's hosted page. */
    public CompletionStage<Result> checkout(Http.Request request) {
        String role = request.session().get("role").orElse("");
        if (request.session().get("accessToken").isEmpty()) {
            return CompletableFuture.completedFuture(redirect("/get-started?mode=login"));
        }
        if (!OWNERS.contains(role)) {
            return CompletableFuture.completedFuture(redirect("/dashboard"));
        }
        Map<String, String[]> form = request.body().asFormUrlEncoded();
        String code = (form == null || !form.containsKey("code") || form.get("code").length == 0) ? "" : form.get("code")[0].trim();
        String key = (form == null || !form.containsKey("key") || form.get("key").length == 0) ? "" : form.get("key")[0].trim();
        String listingId = (form == null || !form.containsKey("listingId") || form.get("listingId").length == 0) ? "" : form.get("listingId")[0].trim();
        String back = listingId.isEmpty() ? "/dashboard/plans" : "/dashboard/listings/" + listingId + "/boost";
        if (!code.matches("[A-Z0-9_]{1,64}") || !key.matches("[A-Za-z0-9-]{8,64}")
                || (!listingId.isEmpty() && !listingId.matches("[A-Za-z0-9-]{1,64}"))) {
            return CompletableFuture.completedFuture(redirect(listingId.isEmpty() || !listingId.matches("[A-Za-z0-9-]{1,64}") ? "/dashboard/plans" : back).flashing("pl", "failed"));
        }

        com.fasterxml.jackson.databind.node.ObjectNode body = play.libs.Json.newObject();
        body.put("code", code);
        if (listingId.isEmpty()) body.putNull("listingId"); else body.put("listingId", listingId);

        String path = "/api/v1/" + role.toLowerCase(Locale.ROOT) + "/offers/checkout";
        return api.postKeyed(request, path, body, key).thenApply(result -> {
            if (result.expired) {
                return redirect("/get-started?mode=login").withNewSession();
            }
            String url = result.json == null ? "" : result.json.path("authorizationUrl").asText("");
            Result next = (result.ok() && url.startsWith("https://"))
                    ? redirect(url)
                    : redirect(back).flashing("pl", "failed");
            if (result.refreshed()) {
                Map<String, String> session = new HashMap<>(request.session().data());
                session.put("accessToken", result.accessToken);
                session.put("refreshToken", result.refreshToken);
                return next.withSession(session);
            }
            return next;
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

    private static boolean isBoost(String code) {
        return code.contains("BOOST");
    }

    private static Offer toOffer(JsonNode o) {
        String code = o.path("code").asText("");
        boolean boost = isBoost(code);
        int photoLimit = o.path("photoLimit").asInt(0);
        int boosts = o.path("includedBoosts").asInt(0);
        return new Offer(
                code,
                label(code),
                days(o.path("days").asLong()),
                boost ? "" : limit(o.path("listingLimit").asInt()),
                (boost || photoLimit == 0) ? "" : (photoLimit < 0 ? "Unlimited photos per listing" : photoLimit + " photos per listing"),
                boosts > 0 ? boosts + " boost included (7 days)" : "",
                price(o.path("amountMinor").asLong()));
    }

    /** Pro, Pro Max, or "7-day boost", whatever the code. */
    private static String label(String code) {
        if (code.contains("PRO_MAX")) return "Pro Max";
        if (code.contains("PRO")) return "Pro";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("BOOST_(\\d+)D").matcher(code);
        if (m.find()) return m.group(1) + "-day boost";
        return pretty(code);
    }

    private static String pretty(String code) {
        if (code.isEmpty()) return "Plan";
        String text = code.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}