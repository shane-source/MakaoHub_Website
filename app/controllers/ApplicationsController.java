package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import models.ApplicationsPage;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class ApplicationsController extends Controller {

    private final AuthedApi api;

    @Inject
    public ApplicationsController(AuthedApi api) {
        this.api = api;
    }

    public CompletionStage<Result> index(Http.Request request, Boolean hidden, Integer show) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());

        int limit = DashSupport.limit(show);
        if (role.equals("RENTER")) return renter(request, limit, hidden != null && hidden);
        return owner(request, role, hidden != null && hidden, limit);
    }

    // ---------- Landlord and Sublessor: applications to their listings ----------

    private CompletionStage<Result> owner(Http.Request request, String role, boolean hiddenView, int limit) {
        String base = role.equals("LANDLORD") ? "/api/v1/landlord" : "/api/v1/sublessor";
        List<String> paths = List.of(
                base + "/applications",
                base + "/listings",
                base + "/application-notes");

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) return DashSupport.toSignIn();

            JsonNode applications = batch.json.get(0);
            JsonNode listings = batch.json.get(1);
            JsonNode notes = batch.json.get(2);

            ApplicationsPage page = new ApplicationsPage();
            page.hiddenView = hiddenView;

            // Without the notes we cannot tell hidden from visible, so nothing is shown (same as Android).
            if (applications != null && applications.isArray() && notes != null && notes.isArray()) {
                page.loaded = true;

                Map<String, String> titles = new HashMap<>();
                if (listings != null && listings.isArray()) {
                    for (JsonNode listing : listings) {
                        titles.put(listing.path("id").asText(""), listing.path("title").asText(""));
                    }
                }
                Set<String> hiddenIds = new HashSet<>();
                Map<String, String> remarks = new HashMap<>();
                for (JsonNode note : notes) {
                    if (note.path("hidden").asBoolean(false)) hiddenIds.add(note.path("applicationId").asText(""));
                    String remark = note.path("remark").asText("");
                    if (!remark.isEmpty()) remarks.put(note.path("applicationId").asText(""), remark);
                }

                List<JsonNode> shown = new ArrayList<>();
                for (JsonNode application : applications) {
                    String status = application.path("status").asText("");
                    if (status.equals("SUBMITTED")) page.fresh++;
                    if (status.equals("UNDER_REVIEW")) page.underReview++;
                    if (status.equals("APPROVED")) page.approved++;

                    boolean isHidden = hiddenIds.contains(application.path("id").asText(""));
                    if (isHidden) page.hiddenCount++;
                    if (isHidden == hiddenView) shown.add(application);
                }

                shown.sort(Comparator.comparing((JsonNode n) -> n.path("updatedAt").asText("")).reversed());
                page.hasMore = shown.size() > limit;
                page.nextShow = limit + 20;

                for (int i = 0; i < Math.min(limit, shown.size()); i++) {
                    JsonNode application = shown.get(i);
                    String status = application.path("status").asText("");
                    String title = titles.get(application.path("listingId").asText(""));
                    String name = application.path("renterName").asText("");
                    ApplicationsPage.Row ownerRow = new ApplicationsPage.Row(
                            (title == null || title.isEmpty()) ? "Listing unavailable" : title,
                            name.isEmpty() ? "Applicant name unavailable" : name,
                            "VERIFIED".equals(application.path("renterVerificationStatus").asText("")),
                            DashSupport.pretty(status),
                            DashSupport.statusClass(status),
                            DashSupport.time(application.path("createdAt").asText("")),
                            application.hasNonNull("rejectionReason")
                                    ? application.path("rejectionReason").asText() : null);
                    ownerRow.moveIn = moveIn(application.path("moveInDate").asText(""));
                    ownerRow.message = application.path("message").asText("");
                    String photo = application.path("renterAvatarUrl").asText("");
                    ownerRow.avatar = photo.startsWith("https://") ? photo : "";
                    String appId = application.path("id").asText("");
                    ownerRow.id = appId.matches("[A-Za-z0-9-]{1,64}") ? appId : "";
                    ownerRow.rawStatus = status;
                    ownerRow.hidden = hiddenView;
                    ownerRow.remark = remarks.getOrDefault(appId, "");
                    page.rows.add(ownerRow);
                }
            }

            Result result = ok(views.html.applications.render(role, page, request));
            return DashSupport.applyTokens(result, batch, request);
        });
    }

    // ---------- Renter: their own applications and bookings ----------

    private CompletionStage<Result> renter(Http.Request request, int limit, boolean archivedView) {
        List<String> paths = List.of(
                "/api/v1/renter/applications",
                "/api/v1/renter/sublessor-applications",
                "/api/v1/renter/bookings",
                "/api/v1/renter/application-state");

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) return DashSupport.toSignIn();

            JsonNode landlord = batch.json.get(0);
            JsonNode shared = batch.json.get(1);
            JsonNode bookings = batch.json.get(2);
            JsonNode states = batch.json.get(3);

            ApplicationsPage page = new ApplicationsPage();
            boolean landlordOk = landlord != null && landlord.isArray();
            boolean sharedOk = shared != null && shared.isArray();

            if (landlordOk || sharedOk) {
                page.loaded = true;

                List<JsonNode> all = new ArrayList<>();
                Map<JsonNode, String> kinds = new HashMap<>();
                if (landlordOk) for (JsonNode a : landlord) { all.add(a); kinds.put(a, "Whole home"); }
                if (sharedOk) for (JsonNode a : shared) { all.add(a); kinds.put(a, "Shared home"); }

                all.sort(Comparator.comparing((JsonNode n) -> n.path("updatedAt").asText("")).reversed());

                // The renter's own archive and delete choices, as in the app. Deleted ones never show.
                Map<String, String> myStates = new HashMap<>();
                if (states != null && states.isArray()) {
                    for (JsonNode st : states) {
                        myStates.put(st.path("kind").asText("") + ":" + st.path("applicationId").asText(""), st.path("state").asText(""));
                    }
                }
                List<JsonNode> keep = new ArrayList<>();
                int archivedCount = 0;
                for (JsonNode a : all) {
                    String kindKey = kinds.get(a).equals("Whole home") ? "LANDLORD" : "SUBLESSOR";
                    String state = myStates.get(kindKey + ":" + a.path("id").asText(""));
                    if ("ARCHIVED".equals(state)) archivedCount++;
                    boolean show = archivedView ? "ARCHIVED".equals(state) : state == null;
                    if (show) keep.add(a);
                }
                all = keep;
                page.hiddenView = archivedView;
                page.hiddenCount = archivedCount;
                page.hasMore = all.size() > limit;
                page.nextShow = limit + 20;

                for (int i = 0; i < Math.min(limit, all.size()); i++) {
                    JsonNode application = all.get(i);
                    String status = application.path("status").asText("");
                    ApplicationsPage.Row renterRow = new ApplicationsPage.Row(
                            kinds.get(application),
                            "",
                            false,
                            DashSupport.pretty(status),
                            DashSupport.statusClass(status),
                            DashSupport.time(application.path("createdAt").asText("")),
                            application.hasNonNull("rejectionReason")
                                    ? application.path("rejectionReason").asText() : null);
                    renterRow.moveIn = moveIn(application.path("moveInDate").asText(""));
                    renterRow.message = application.path("message").asText("");
                    String renterAppId = application.path("id").asText("");
                    renterRow.id = renterAppId.matches("[A-Za-z0-9-]{1,64}") ? renterAppId : "";
                    renterRow.rawStatus = status;
                    renterRow.source = kinds.get(application).equals("Whole home") ? "LANDLORD" : "SUBLESSOR";
                    // A home the renter booked and paid leaves the marketplace, but stays visible to them.
                    if (bookings != null && bookings.isArray()) {
                        for (JsonNode b : bookings) {
                            boolean mine = renterAppId.equals(b.path("sourceApplicationId").asText(""));
                            boolean paid = b.hasNonNull("releasedAt")
                                    || b.path("status").asText("").equals("PAID_HELD")
                                    || b.path("status").asText("").equals("ACTIVE");
                            String listingId = application.path("listingId").asText("");
                            if (mine && paid && listingId.matches("[A-Za-z0-9-]{1,64}")) {
                                renterRow.booked = true;
                                renterRow.homeHref = "/dashboard/homes/" + renterRow.source + "/" + listingId;
                                break;
                            }
                        }
                    }
                    page.rows.add(renterRow);
                }
            }

            if (bookings != null && bookings.isArray()) {
                page.bookingsLoaded = true;
                for (JsonNode b : bookings) {
                    boolean whole = b.path("listingKind").asText("").equals("LANDLORD_LISTING");
                    String renterName = b.path("renterName").asText("");
                    String ownerName = b.path("ownerName").asText("");
                    page.bookings.add(new ApplicationsPage.BookingRow(
                            (renterName.isEmpty() ? "You" : renterName) + " · "
                                    + (ownerName.isEmpty() ? (whole ? "Landlord" : "Sublessor") : ownerName),
                            whole ? "Whole home" : "Shared home",
                            DashSupport.pretty(b.path("status").asText("")),
                            DashSupport.kes(b.path("totalAmountMinor").asLong() / 100)));
                }
            }

            Result result = ok(views.html.applications.render("RENTER", page, request));
            return DashSupport.applyTokens(result, batch, request);
        });
    }

    private static String moveIn(String iso) {
        try {
            return java.time.LocalDate.parse(iso)
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH));
        } catch (Exception e) {
            return "";
        }
    }
}
