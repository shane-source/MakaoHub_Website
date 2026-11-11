package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import models.DashboardData;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class DashboardController extends Controller {

    private static final Set<String> ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");

    private final AuthedApi api;

    @Inject
    public DashboardController(AuthedApi api) {
        this.api = api;
    }

    public CompletionStage<Result> index(Http.Request request) {
        boolean signedIn = request.session().get("accessToken").isPresent();
        String role = request.session().get("role").orElse("");
        if (!signedIn || !ROLES.contains(role)) {
            return CompletableFuture.completedFuture(redirect("/get-started?mode=login"));
        }
        String name = request.session().get("fullName").orElse("");

        return api.getAll(request, pathsFor(role)).thenApply(batch -> {
            if (batch.expired) {
                // The session is no longer valid: start again from sign in.
                return redirect("/get-started?mode=login").withNewSession();
            }

            DashboardData data = new DashboardData();
            if (role.equals("LANDLORD")) {
                fillLandlord(data, batch.json);
            } else if (role.equals("SUBLESSOR")) {
                fillSublessor(data, batch.json);
            } else {
                fillRenter(data, batch.json);
            }

            Result page = ok(views.html.dashboard.render(role, name, data, request));
            if (batch.refreshed()) {
                Map<String, String> session = new HashMap<>(request.session().data());
                session.put("accessToken", batch.accessToken);
                session.put("refreshToken", batch.refreshToken);
                return page.withSession(session);
            }
            return page;
        });
    }

    // ---------- Which backend calls each role needs ----------

    private static List<String> pathsFor(String role) {
        if (role.equals("LANDLORD")) {
            return List.of(
                    "/api/v1/landlord/verification",
                    "/api/v1/landlord/listings/dashboard",
                    "/api/v1/landlord/applications",
                    "/api/v1/landlord/listings");
        }
        if (role.equals("SUBLESSOR")) {
            return List.of(
                    "/api/v1/sublessor/verification",
                    "/api/v1/sublessor/listings",
                    "/api/v1/sublessor/applications");
        }
        return List.of(
                "/api/v1/renter/verification",
                "/api/v1/renter/homes?page=0&saved=false");
    }

    // ---------- Landlord: counts come from the dashboard summary ----------

    private static void fillLandlord(DashboardData d, List<JsonNode> json) {
        JsonNode verification = json.get(0);
        JsonNode summary = json.get(1);
        JsonNode applications = json.get(2);
        JsonNode listings = json.get(3);

        d.loaded = verification != null || summary != null || applications != null || listings != null;
        setVerification(d, verification);

        if (summary != null) {
            d.active = String.valueOf(summary.path("publishedListings").asLong());
            d.drafts = String.valueOf(summary.path("draftListings").asLong());
        }
        fillApplications(d, applications, listings);
    }

    // ---------- Sublessor: counts come from their listings ----------

    private static void fillSublessor(DashboardData d, List<JsonNode> json) {
        JsonNode verification = json.get(0);
        JsonNode listings = json.get(1);
        JsonNode applications = json.get(2);

        d.loaded = verification != null || listings != null || applications != null;
        setVerification(d, verification);

        if (listings != null && listings.isArray()) {
            long active = 0;
            long drafts = 0;
            for (JsonNode listing : listings) {
                String status = listing.path("status").asText("");
                if (status.equals("PUBLISHED")) active++;
                if (status.equals("DRAFT")) drafts++;
            }
            d.active = String.valueOf(active);
            d.drafts = String.valueOf(drafts);
        }
        fillApplications(d, applications, listings);
    }

    // ---------- Renter: the first page of homes ----------

    private static void fillRenter(DashboardData d, List<JsonNode> json) {
        JsonNode verification = json.get(0);
        JsonNode homes = json.get(1);

        d.loaded = verification != null || homes != null;
        setVerification(d, verification);

        if (homes != null && homes.path("items").isArray()) {
            d.homesLoaded = true;
            for (JsonNode home : homes.path("items")) {
                List<String> parts = new ArrayList<>();
                String area = home.path("areaLabel").asText("");
                if (!area.isEmpty()) parts.add(area);
                if (home.hasNonNull("bedroomCount")) {
                    int beds = home.path("bedroomCount").asInt();
                    parts.add(beds + (beds == 1 ? " Bed" : " Beds"));
                }
                int baths = home.path("bathroomCount").asInt();
                parts.add(baths + (baths == 1 ? " Bath" : " Baths"));

                d.homes.add(new DashboardData.HomeCard(
                        home.path("title").asText("Home"),
                        String.join(" · ", parts),
                        kes(home.path("monthlyRentKes").asLong()) + "/mo",
                        home.path("sponsored").asBoolean(false)));
            }
        }
    }

    // ---------- Shared pieces ----------

    private static void setVerification(DashboardData d, JsonNode verification) {
        if (verification == null) return;
        String status = verification.path("status").asText("NOT_STARTED");
        d.verification = status;
        d.verificationLabel = pretty(status);
    }

    // Pending count and the three most recent applications.
    private static void fillApplications(DashboardData d, JsonNode applications, JsonNode listings) {
        if (applications == null || !applications.isArray()) return;
        d.applicationsLoaded = true;

        Map<String, String> titles = new HashMap<>();
        if (listings != null && listings.isArray()) {
            for (JsonNode listing : listings) {
                titles.put(listing.path("id").asText(""), listing.path("title").asText(""));
            }
        }

        long pending = 0;
        List<JsonNode> all = new ArrayList<>();
        for (JsonNode application : applications) {
            String status = application.path("status").asText("");
            if (status.equals("SUBMITTED") || status.equals("UNDER_REVIEW")) pending++;
            all.add(application);
        }
        d.pending = String.valueOf(pending);

        all.sort(Comparator.comparing((JsonNode n) -> n.path("updatedAt").asText("")).reversed());
        for (int i = 0; i < Math.min(3, all.size()); i++) {
            JsonNode application = all.get(i);
            String title = titles.get(application.path("listingId").asText(""));
            d.applications.add(new DashboardData.ApplicationRow(
                    (title == null || title.isEmpty()) ? "Your listing" : title,
                    pretty(application.path("status").asText(""))));
        }
    }

    private static String pretty(String value) {
        if (value == null || value.isEmpty()) return "";
        String text = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String kes(long value) {
        return "KSh " + String.format(Locale.UK, "%,d", value);
    }
}