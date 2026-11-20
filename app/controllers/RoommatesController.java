package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import models.RoommatesPage;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

/** Sublessors: roommate interests in their spaces, housemate preferences, and opted-in renters. */
public class RoommatesController extends Controller {

    private static final String BASE = "/api/v1/sublessor";
    private static final Set<String> ACTIONS = Set.of("review", "accept", "decline");

    /** Same questions and answers as the Android app. */
    private static final String[][] QUESTIONS = {
        {"quietSocial", "Quiet / social"},
        {"sleepSchedule", "Sleep schedule"},
        {"cleanliness", "Cleanliness"},
        {"cooking", "Cooking"},
        {"guests", "Guests"},
        {"smoking", "Smoking"},
        {"pets", "Pets"}};
    private static final Map<String, LinkedHashMap<String, String>> CHOICES = new LinkedHashMap<>();
    static {
        CHOICES.put("quietSocial", opts("QUIET", "Mostly quiet", "SOCIAL", "Social", "BALANCED", "A balance of both"));
        CHOICES.put("sleepSchedule", opts("EARLY", "Early riser", "NIGHT", "Night owl", "FLEXIBLE", "Flexible"));
        CHOICES.put("cleanliness", opts("DAILY", "Daily shared-space tidy-up", "WEEKLY", "Weekly tidy-up"));
        CHOICES.put("cooking", opts("REGULAR", "Regular cooking", "OCCASIONAL", "Occasional cooking", "NONE", "No cooking"));
        CHOICES.put("guests", opts("ASK_FIRST", "Ask first", "OCCASIONAL", "Occasional guests", "FREQUENT", "Frequent guests"));
        CHOICES.put("smoking", opts("NO_INDOOR", "No indoor smoking", "OUTSIDE_ONLY", "Outside only", "SMOKER", "Smoking household"));
        CHOICES.put("pets", opts("NONE", "No pets", "PETS_OK", "Pets welcome", "HAS_PETS", "Pets in the home"));
    }

    private static LinkedHashMap<String, String> opts(String... pairs) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i], pairs[i + 1]);
        return map;
    }

    private final AuthedApi api;

    @Inject
    public RoommatesController(AuthedApi api) {
        this.api = api;
    }

    private static boolean notSublessor(String role) {
        return role == null || !role.equals("SUBLESSOR");
    }

    // ---------- Interests and discovery ----------

    public CompletionStage<Result> index(Http.Request request, Integer pageNumber) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());
        if (notSublessor(role)) return DashSupport.done(redirect("/dashboard"));

        int number = (pageNumber == null || pageNumber < 0) ? 0 : Math.min(pageNumber, 500);
        List<String> paths = List.of(
                BASE + "/roommate-interests",
                BASE + "/listings",
                BASE + "/roommate-preferences",
                BASE + "/roommate-discovery?page=" + number + "&size=20");

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) return DashSupport.toSignIn();

            JsonNode interests = batch.json.get(0);
            JsonNode listings = batch.json.get(1);
            JsonNode prefs = batch.json.get(2);
            JsonNode discovery = batch.json.get(3);

            RoommatesPage page = new RoommatesPage();
            page.page = number;

            Map<String, String> titles = new HashMap<>();
            if (listings != null && listings.isArray()) {
                for (JsonNode listing : listings) {
                    titles.put(listing.path("id").asText(""), listing.path("title").asText(""));
                }
            }

            if (interests != null && interests.isArray()) {
                page.interestsLoaded = true;
                for (JsonNode interest : interests) {
                    String status = interest.path("status").asText("");
                    String id = interest.path("id").asText("");
                    String title = titles.get(interest.path("sublessorListingId").asText(""));
                    page.interests.add(new RoommatesPage.Interest(
                            id.matches("[A-Za-z0-9-]{1,64}") ? id : "",
                            (title == null || title.isEmpty()) ? "Shared space" : title,
                            DashSupport.pretty(status),
                            DashSupport.statusClass(status),
                            status.equals("SUBMITTED") || status.equals("UNDER_REVIEW"),
                            status.equals("ACCEPTED")));
                }
            }

            if (prefs != null && prefs.hasNonNull("quietSocial")) {
                page.hasPreferences = true;
                page.optedIn = prefs.path("discoveryOptIn").asBoolean(false);
                page.summary = summary(prefs);
            }

            if (discovery != null && discovery.path("items").isArray()) {
                page.discoveryLoaded = true;
                page.hasNext = discovery.path("hasNext").asBoolean(false);
                for (JsonNode person : discovery.path("items")) {
                    JsonNode p = person.path("preferences");
                    String personId = person.path("userId").asText("");
                    page.people.add(new RoommatesPage.Person(
                            personId.matches("[A-Za-z0-9-]{1,64}") ? personId : "",
                            person.path("displayName").asText("Renter"),
                            p.path("areaLabel").asText(""),
                            budget(p),
                            traits(p)));
                }
            }

            Result result = ok(views.html.roommates.render(role, page, request));
            return DashSupport.applyTokens(result, batch, request);
        });
    }

    // ---------- One renter in full ----------

    public CompletionStage<Result> person(Http.Request request, String id) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());
        if (notSublessor(role) || !id.matches("[A-Za-z0-9-]{1,64}")) return DashSupport.done(redirect("/dashboard/roommates"));

        return api.getAll(request, List.of(BASE + "/roommate-discovery/" + id, BASE + "/roommate-preferences"))
                .thenApply(batch -> {
                    if (batch.expired) return DashSupport.toSignIn();
                    JsonNode person = batch.json.get(0);
                    JsonNode mine = batch.json.get(1);
                    RoommatesPage.Profile profile = new RoommatesPage.Profile();
                    if (person != null) {
                        JsonNode p = person.path("preferences");
                        profile.loaded = true;
                        profile.name = person.path("displayName").asText("Renter");
                        profile.area = p.path("areaLabel").asText("");
                        profile.about = p.path("about").asText("");
                        profile.employment = p.path("employment").asText("");
                        profile.moveIn = p.path("moveInDate").asText("");
                        profile.budget = budget(p);
                        for (String[] q : QUESTIONS) {
                            if (mine == null || !mine.hasNonNull(q[0])) break;
                            if (mine.path(q[0]).asText("").equals(p.path(q[0]).asText(""))) profile.same.add(q[1]);
                            else profile.different.add(q[1]);
                        }
                    }
                    Result result = ok(views.html.roommateProfile.render(role, profile, request));
                    return DashSupport.applyTokens(result, batch, request);
                });
    }

    // ---------- Accept or decline an interest ----------

    public CompletionStage<Result> interest(Http.Request request, String id, String action) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());
        if (notSublessor(role) || !ACTIONS.contains(action) || !id.matches("[A-Za-z0-9-]{1,64}")) {
            return DashSupport.done(redirect("/dashboard/roommates"));
        }
        return api.post(request, BASE + "/roommate-interests/" + id + "/" + action, null).thenApply(r ->
                ApplicationActionsController.finish(request, "/dashboard/roommates", r.expired, r.ok(), r.json,
                        r.accessToken, r.refreshToken));
    }

    // ---------- Housemate preferences ----------

    public CompletionStage<Result> preferencesPage(Http.Request request) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());
        if (notSublessor(role)) return DashSupport.done(redirect("/dashboard"));

        return api.getAll(request, List.of(BASE + "/roommate-preferences")).thenApply(batch -> {
            if (batch.expired) return DashSupport.toSignIn();
            JsonNode prefs = batch.json.get(0);
            Result result = ok(views.html.roommatePreferences.render(role, form(prefs), request));
            return DashSupport.applyTokens(result, batch, request);
        });
    }

    public CompletionStage<Result> savePreferences(Http.Request request) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());
        if (notSublessor(role)) return DashSupport.done(redirect("/dashboard"));

        Map<String, String[]> form = request.body().asFormUrlEncoded();
        ObjectNode body = Json.newObject();
        for (String[] q : QUESTIONS) {
            String value = value(form, q[0]);
            if (!CHOICES.get(q[0]).containsKey(value)) {
                return DashSupport.done(redirect("/dashboard/roommates/preferences").flashing("rm", "answers"));
            }
            body.put(q[0], value);
        }
        Long min = number(value(form, "minBudget"));
        Long max = number(value(form, "maxBudget"));
        if (min == null || max == null || min <= 0 || max < min) {
            return DashSupport.done(redirect("/dashboard/roommates/preferences").flashing("rm", "budget"));
        }
        body.put("budgetMinKes", min);
        body.put("budgetMaxKes", max);
        body.put("discoveryOptIn", !value(form, "optIn").isEmpty());

        // Keep the details that were saved elsewhere (area, about, work, move-in date).
        return api.getAll(request, List.of(BASE + "/roommate-preferences")).thenCompose(batch -> {
            if (batch.expired) return DashSupport.done(DashSupport.toSignIn());
            JsonNode old = batch.json.get(0);
            if (old != null) {
                for (String key : List.of("areaLabel", "about", "employment", "moveInDate")) {
                    if (old.hasNonNull(key)) body.put(key, old.path(key).asText());
                }
            }
            return api.put(request, BASE + "/roommate-preferences", body).thenApply(r -> {
                if (r.expired) return DashSupport.toSignIn();
                Result result = redirect(r.ok() ? "/dashboard/roommates" : "/dashboard/roommates/preferences")
                        .flashing("rm", r.ok() ? "saved" : "failed");
                String access = r.accessToken != null ? r.accessToken : batch.accessToken;
                String refresh = r.accessToken != null ? r.refreshToken : batch.refreshToken;
                if (access != null) {
                    Map<String, String> session = new HashMap<>(request.session().data());
                    session.put("accessToken", access);
                    session.put("refreshToken", refresh);
                    result = result.withSession(session);
                }
                return result;
            });
        });
    }

    // ---------- Helpers ----------

    private static String value(Map<String, String[]> form, String name) {
        if (form == null || form.get(name) == null || form.get(name).length == 0) return "";
        return form.get(name)[0].trim();
    }

    private static Long number(String text) {
        try {
            return text.isEmpty() ? null : Long.parseLong(text.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String label(String field, JsonNode prefs) {
        String key = prefs.path(field).asText("");
        String text = CHOICES.get(field).get(key);
        return text == null ? "" : text;
    }

    private static String budget(JsonNode p) {
        if (!p.hasNonNull("budgetMinKes") || !p.hasNonNull("budgetMaxKes")) return "";
        return DashSupport.kes(p.path("budgetMinKes").asLong()) + " – "
                + DashSupport.kes(p.path("budgetMaxKes").asLong()) + " / month";
    }

    private static String traits(JsonNode p) {
        List<String> parts = new ArrayList<>();
        for (String field : List.of("quietSocial", "sleepSchedule", "pets")) {
            String text = label(field, p);
            if (!text.isEmpty()) parts.add(text);
        }
        return String.join(" · ", parts);
    }

    private static String summary(JsonNode prefs) {
        List<String> parts = new ArrayList<>();
        for (String[] q : QUESTIONS) {
            String text = label(q[0], prefs);
            if (!text.isEmpty()) parts.add(text);
        }
        String b = budget(prefs);
        if (!b.isEmpty()) parts.add(b);
        return String.join(" · ", parts);
    }

    private static RoommatesPage.PreferencesForm form(JsonNode prefs) {
        RoommatesPage.PreferencesForm f = new RoommatesPage.PreferencesForm();
        boolean have = prefs != null && prefs.hasNonNull("quietSocial");
        f.loaded = true;
        for (String[] q : QUESTIONS) {
            List<String[]> options = new ArrayList<>();
            for (Map.Entry<String, String> e : CHOICES.get(q[0]).entrySet()) {
                options.add(new String[] {e.getKey(), e.getValue()});
            }
            f.questions.add(new RoommatesPage.Question(q[0], q[1], options, have ? prefs.path(q[0]).asText("") : ""));
        }
        if (have) {
            f.minBudget = String.valueOf(prefs.path("budgetMinKes").asLong());
            f.maxBudget = String.valueOf(prefs.path("budgetMaxKes").asLong());
            f.optIn = prefs.path("discoveryOptIn").asBoolean(false);
        }
        return f;
    }
}
