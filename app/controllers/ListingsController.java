package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import models.ListingsPage;
import models.SavedPage;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class ListingsController extends Controller {

    private static final Set<String> FILTERS = Set.of("PUBLISHED", "DRAFT", "PAUSED");

    private final AuthedApi api;

    @Inject
    public ListingsController(AuthedApi api) {
        this.api = api;
    }

    // ---------- Landlord and Sublessor: their own listings ----------

    public CompletionStage<Result> index(Http.Request request, String status, Integer show) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());
        if (role.equals("RENTER")) return DashSupport.done(redirect("/dashboard/saved"));

        String filter = FILTERS.contains(status) ? status : "PUBLISHED";
        int limit = DashSupport.limit(show);
        String base = role.equals("LANDLORD") ? "/api/v1/landlord" : "/api/v1/sublessor";

        List<String> paths = List.of(
                base + "/listings",
                base + "/applications",
                base + "/offers/listing-card-metrics");

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) return DashSupport.toSignIn();

            JsonNode listings = batch.json.get(0);
            JsonNode applications = batch.json.get(1);
            JsonNode metrics = batch.json.get(2);

            ListingsPage page = new ListingsPage();
            page.filter = filter;
            page.filterLabel = filter.toLowerCase(Locale.ROOT);

            if (listings != null && listings.isArray()) {
                page.loaded = true;
                page.total = listings.size();

                Map<String, Integer> leads = new HashMap<>();
                if (applications != null && applications.isArray()) {
                    for (JsonNode application : applications) {
                        leads.merge(application.path("listingId").asText(""), 1, Integer::sum);
                    }
                }
                Map<String, JsonNode> cardMetrics = new HashMap<>();
                if (metrics != null && metrics.isArray()) {
                    for (JsonNode m : metrics) cardMetrics.put(m.path("listingId").asText(""), m);
                }

                int matching = 0;
                for (JsonNode listing : listings) {
                    String itemStatus = listing.path("status").asText("");
                    if (itemStatus.equals("PUBLISHED")) page.active++;
                    if (itemStatus.equals("DRAFT")) page.drafts++;
                    if (itemStatus.equals("PAUSED")) page.paused++;
                    if (!itemStatus.equals(filter)) continue;

                    matching++;
                    if (page.rows.size() >= limit) continue;

                    String id = listing.path("id").asText("");
                    JsonNode m = cardMetrics.get(id);
                    String views = m != null
                            ? m.path("viewsToday").asLong() + " views today"
                            : "Views unavailable";
                    String boost = (m != null && m.hasNonNull("boostExpiresAt"))
                            ? DashSupport.boostLabel(m.path("boostExpiresAt").asText())
                            : null;

                    page.rows.add(new ListingsPage.ListingRow(
                            listing.path("title").asText("Untitled listing"),
                            DashSupport.kes(listing.path("monthlyRentKes").asLong()),
                            details(role, listing),
                            itemStatus,
                            DashSupport.pretty(itemStatus),
                            listing.hasNonNull("availableFrom") ? listing.path("availableFrom").asText() : null,
                            leads.getOrDefault(id, 0),
                            views,
                            boost));
                }
                page.hasMore = matching > limit;
                page.nextShow = limit + 20;
            }

            Result result = ok(views.html.listings.render(role, page, request));
            return DashSupport.applyTokens(result, batch, request);
        });
    }

    private static String details(String role, JsonNode listing) {
        if (role.equals("LANDLORD")) {
            return listing.path("bedroomCount").asInt() + " bed · "
                    + listing.path("bathroomCount").asInt() + " bath · "
                    + listing.path("propertyType").asText("").toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return listing.path("spaceType").asText("").equals("SPARE_ROOM")
                ? "Spare room · Looking for a roommate"
                : "Servant quarter";
    }

    // ---------- Renter: saved homes ----------

    public CompletionStage<Result> saved(Http.Request request, Integer pageNumber) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());
        if (!role.equals("RENTER")) return DashSupport.done(redirect("/dashboard"));

        int number = (pageNumber == null || pageNumber < 0) ? 0 : Math.min(pageNumber, 500);

        return api.getAll(request, List.of("/api/v1/renter/homes?page=" + number + "&saved=true"))
                .thenApply(batch -> {
                    if (batch.expired) return DashSupport.toSignIn();

                    JsonNode homes = batch.json.get(0);
                    SavedPage page = new SavedPage();
                    page.page = number;
                    if (homes != null && homes.path("items").isArray()) {
                        page.loaded = true;
                        page.homes = DashSupport.parseHomes(homes);
                        page.hasNext = homes.path("hasNext").asBoolean(false);
                    }

                    Result result = ok(views.html.saved.render(page, request));
                    return DashSupport.applyTokens(result, batch, request);
                });
    }
}