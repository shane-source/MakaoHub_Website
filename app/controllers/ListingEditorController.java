package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.twirl.api.Content;
import services.OwnerApi;

public class ListingEditorController extends Controller {

    private static final Logger LOG = LoggerFactory.getLogger(ListingEditorController.class);

    private static final Set<String> OWNERS = Set.of("LANDLORD", "SUBLESSOR");
    private static final Set<String> PROPERTY = Set.of("APARTMENT", "HOUSE", "STUDIO", "BEDSITTER", "ROOM");
    private static final Set<String> SPACE = Set.of("SPARE_ROOM", "SERVANT_QUARTER");
    private static final String LOGIN = "/get-started?mode=login";

    /** Every field of both forms, as text, so a form can be shown again exactly as it was typed. */
    public record Form(
            String id, String status,
            String title, String description, String kind,
            String rent, String deposit, String bedrooms, String bathrooms, String available,
            boolean furnished, boolean water, boolean security, boolean parking, boolean balcony, boolean pets,
            String household, String occupants, String perRoom, String lifestyle,
            boolean amWater, boolean amWifi, boolean amSecurity, boolean amParking, boolean amPets) {

        static Form blank(String role) {
            boolean landlord = role.equals("LANDLORD");
            return new Form("", "", "", "", landlord ? "APARTMENT" : "SPARE_ROOM",
                    "", landlord ? "0" : "", "1", "1", "",
                    false, false, false, false, false, false,
                    "", "", "", "",
                    false, false, false, false, false);
        }
    }

    /** The tokens for this request. Renewed tokens are saved to the session when the page is sent back. */
    private static final class Ctx {
        String access;
        String refresh;
        boolean renewed;

        Ctx(Http.Request request) {
            access = request.session().get("accessToken").orElse("");
            refresh = request.session().get("refreshToken").orElse("");
        }

        void take(OwnerApi.Reply reply) {
            if (reply.access() != null) {
                access = reply.access();
                refresh = reply.refresh();
                renewed = true;
            }
        }
    }

    private final OwnerApi api;

    @Inject
    public ListingEditorController(OwnerApi api) {
        this.api = api;
    }

    // ---------- New listing ----------

    public CompletionStage<Result> newPage(Http.Request request) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);
        String role = role(request);
        Ctx c = new Ctx(request);

        return call(c, "GET", owner(role) + "/verification", null).thenApply(v -> {
            if (v.expired()) return redirect(LOGIN).withNewSession();
            String status = v.ok() ? v.text("status") : "";
            String notice = (status.isEmpty() || status.equals("VERIFIED")) ? ""
                    : "Your account isn't verified yet. You can try saving a draft. If it is refused, finish verification from your Profile page.";
            return finish(c, request, ok(page(request, role, Form.blank(role), "", notice, false)));
        });
    }

    public CompletionStage<Result> create(Http.Request request) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);
        String role = role(request);

        Form form = read(role, request.body().asFormUrlEncoded(), "", "DRAFT");
        String problem = check(role, form);
        if (problem != null) return done(badRequest(page(request, role, form, problem, "", false)));

        Ctx c = new Ctx(request);
        String path = role.equals("LANDLORD") ? "/api/v1/landlord/listings/drafts" : "/api/v1/sublessor/listings";

        return call(c, "POST", path, listingBody(role, form)).thenCompose(created -> {
            if (created.expired()) return done(redirect(LOGIN).withNewSession());
            String id = created.text("id");
            if (!created.ok() || !isSafeId(id)) {
                LOG.warn("listing create failed: status={} body={}", created.status(), created.json());
                return done(finish(c, request, badRequest(page(request, role, form, created.message(), "", false))));
            }
            return saved(c, request, role, id, form);
        });
    }

    // ---------- Edit a draft ----------

    public CompletionStage<Result> editPage(Http.Request request, String id) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);
        if (!isSafeId(id)) return done(redirect("/dashboard/listings"));
        String role = role(request);
        Ctx c = new Ctx(request);

        return call(c, "GET", owner(role) + "/listings", null).thenCompose(list -> {
            if (list.expired()) return done(redirect(LOGIN).withNewSession());

            JsonNode found = null;
            JsonNode items = list.json() == null ? null
                    : (list.json().isArray() ? list.json() : list.json().path("items"));
            if (items != null && items.isArray()) {
                for (JsonNode n : items) {
                    if (id.equals(n.path("id").asText(""))) { found = n; break; }
                }
            }
            if (found == null) return done(finish(c, request, redirect("/dashboard/listings")));

            Form base = fromListing(role, found);
            boolean locked = !base.status().equals("DRAFT");
            String notice = locked
                    ? "This listing is " + base.status().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + ", so it can't be edited here. Changing a listing that is in review or live comes in a later step."
                    : "";

            if (role.equals("LANDLORD")) {
                return done(finish(c, request, ok(page(request, role, base, "", notice, locked))));
            }
            return call(c, "GET", "/api/v1/sublessor/listings/" + id + "/household", null).thenApply(h -> {
                if (h.expired()) return redirect(LOGIN).withNewSession();
                Form withHousehold = h.ok() && h.json() != null ? addHousehold(base, h.json()) : base;
                return finish(c, request, ok(page(request, role, withHousehold, "", notice, locked)));
            });
        });
    }

    public CompletionStage<Result> update(Http.Request request, String id) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);
        if (!isSafeId(id)) return done(redirect("/dashboard/listings"));
        String role = role(request);

        Form form = read(role, request.body().asFormUrlEncoded(), id, "DRAFT");
        String problem = check(role, form);
        if (problem != null) return done(badRequest(page(request, role, form, problem, "", false)));

        Ctx c = new Ctx(request);
        // The landlord API updates with PATCH, the sublessor API with PUT.
        String method = role.equals("LANDLORD") ? "PATCH" : "PUT";

        return call(c, method, owner(role) + "/listings/" + id, listingBody(role, form)).thenCompose(updated -> {
            if (updated.expired()) return done(redirect(LOGIN).withNewSession());
            if (!updated.ok()) {
                LOG.warn("listing update failed: status={} body={}", updated.status(), updated.json());
                return done(finish(c, request, badRequest(page(request, role, form, updated.message(), "", false))));
            }
            return saved(c, request, role, id, form);
        });
    }

    // ---------- After the listing itself is saved ----------

    /** A sublessor's household details are a second save. Both end on the edit page with a message. */
    private CompletionStage<Result> saved(Ctx c, Http.Request request, String role, String id, Form form) {
        String page = "/dashboard/listings/" + id + "/edit";

        if (role.equals("LANDLORD")) {
            return done(finish(c, request, redirect(page).flashing("listing", "saved")));
        }
        return call(c, "PUT", "/api/v1/sublessor/listings/" + id + "/household", householdBody(form)).thenApply(h -> {
            if (h.expired()) return redirect(LOGIN).withNewSession();
            if (!h.ok()) LOG.warn("household save failed: status={} body={}", h.status(), h.json());
            return finish(c, request, redirect(page).flashing("listing", h.ok() ? "saved" : "household-failed"));
        });
    }

    // ---------- Reading and checking the form ----------

    private static Form read(String role, Map<String, String[]> data, String id, String status) {
        return new Form(id, status,
                val(data, "title"), val(data, "description"), val(data, "kind"),
                val(data, "rent"), val(data, "deposit"), val(data, "bedrooms"), val(data, "bathrooms"), val(data, "available"),
                on(data, "furnished"), on(data, "water"), on(data, "security"), on(data, "parking"), on(data, "balcony"), on(data, "pets"),
                val(data, "household"), val(data, "occupants"), val(data, "perRoom"), val(data, "lifestyle"),
                on(data, "amWater"), on(data, "amWifi"), on(data, "amSecurity"), on(data, "amParking"), on(data, "amPets"));
    }

    /** The same rules as the Android app. Returns a message, or null when everything is fine. */
    private static String check(String role, Form f) {
        boolean landlord = role.equals("LANDLORD");

        if (f.title().isEmpty() || f.title().length() > 150) return "Enter a title of up to 150 characters.";
        if (f.description().isEmpty() || f.description().length() > 3000) {
            return landlord ? "Enter a description of up to 3,000 characters."
                    : "Describe the shared home in up to 3,000 characters.";
        }
        if (landlord && !PROPERTY.contains(f.kind())) return "Select a supported property type.";
        if (!landlord && !SPACE.contains(f.kind())) return "Choose spare room or servant quarter.";

        Long rent = number(f.rent());
        if (rent == null || rent <= 0) return "Enter a positive monthly rent in whole KSh.";

        if (landlord) {
            Long deposit = number(f.deposit());
            if (deposit == null) return "Enter a deposit of zero or more in whole KSh.";
            Long bedrooms = number(f.bedrooms());
            if (bedrooms == null || bedrooms > 20) return "Bedrooms must be between 0 and 20.";
        }

        Long bathrooms = number(f.bathrooms());
        if (bathrooms == null || bathrooms < 1 || bathrooms > 20) return "Bathrooms must be between 1 and 20.";

        if (!f.available().isEmpty()) {
            try {
                LocalDate.parse(f.available());
            } catch (Exception e) {
                return "Use a valid availability date.";
            }
        }

        if (!landlord) {
            if (!f.deposit().isEmpty() && number(f.deposit()) == null) return "Enter a non-negative deposit in whole KSh.";
            if (f.household().length() > 600 || f.lifestyle().length() > 600) {
                return "Keep household descriptions within 600 characters.";
            }
            if (!f.occupants().isEmpty()) {
                Long n = number(f.occupants());
                if (n == null || n < 1 || n > 20) return "Enter 1 to 20 household occupants.";
            }
            if (!f.perRoom().isEmpty()) {
                Long n = number(f.perRoom());
                if (n == null || n < 1 || n > 10) return "Enter 1 to 10 people per room.";
            }
        }
        return null;
    }

    // ---------- What is sent to the backend ----------

    private static ObjectNode listingBody(String role, Form f) {
        ObjectNode b = Json.newObject();
        b.put("title", f.title());
        b.put("description", f.description());
        b.put("monthlyRentKes", number(f.rent()));
        b.put("furnished", f.furnished());
        if (!f.available().isEmpty()) b.put("availableFrom", f.available());

        if (role.equals("LANDLORD")) {
            b.put("propertyType", f.kind());
            b.put("bedroomCount", number(f.bedrooms()).intValue());
            b.put("bathroomCount", number(f.bathrooms()).intValue());
            b.put("depositKes", number(f.deposit()));
            b.put("waterAvailable", f.water());
            b.put("securityAvailable", f.security());
            b.put("parkingAvailable", f.parking());
            b.put("balcony", f.balcony());
            b.put("petsAllowed", f.pets());
        } else {
            b.put("spaceType", f.kind());
            b.put("bathroomCount", number(f.bathrooms()).intValue());
        }
        return b;
    }

    private static ObjectNode householdBody(Form f) {
        ObjectNode b = Json.newObject();
        if (!f.deposit().isEmpty()) b.put("depositKes", number(f.deposit()));
        if (!f.household().isEmpty()) b.put("currentHousehold", f.household());
        if (!f.occupants().isEmpty()) b.put("totalOccupants", number(f.occupants()).intValue());
        if (!f.perRoom().isEmpty()) b.put("peoplePerRoom", number(f.perRoom()).intValue());
        if (!f.lifestyle().isEmpty()) b.put("lifestyle", f.lifestyle());

        ArrayNode amenities = b.putArray("amenities");
        if (f.amWater()) amenities.add("WATER");
        if (f.amWifi()) amenities.add("WIFI");
        if (f.amSecurity()) amenities.add("SECURITY");
        if (f.amParking()) amenities.add("PARKING");
        if (f.amPets()) amenities.add("PETS");
        return b;
    }

    // ---------- Showing a saved listing again ----------

    private static Form fromListing(String role, JsonNode n) {
        boolean landlord = role.equals("LANDLORD");
        return new Form(
                str(n, "id"), str(n, "status"),
                str(n, "title"), str(n, "description"), str(n, landlord ? "propertyType" : "spaceType"),
                str(n, "monthlyRentKes"), landlord ? str(n, "depositKes") : "",
                landlord ? str(n, "bedroomCount") : "", str(n, "bathroomCount"), str(n, "availableFrom"),
                flag(n, "furnished"), flag(n, "waterAvailable"), flag(n, "securityAvailable"),
                flag(n, "parkingAvailable"), flag(n, "balcony"), flag(n, "petsAllowed"),
                "", "", "", "",
                false, false, false, false, false);
    }

    private static Form addHousehold(Form f, JsonNode h) {
        Set<String> amenities = new java.util.HashSet<>();
        JsonNode list = h.path("amenities");
        if (list.isArray()) list.forEach(a -> amenities.add(a.asText("")));

        return new Form(f.id(), f.status(), f.title(), f.description(), f.kind(),
                f.rent(), str(h, "depositKes"), f.bedrooms(), f.bathrooms(), f.available(),
                f.furnished(), f.water(), f.security(), f.parking(), f.balcony(), f.pets(),
                str(h, "currentHousehold"), str(h, "totalOccupants"), str(h, "peoplePerRoom"), str(h, "lifestyle"),
                amenities.contains("WATER"), amenities.contains("WIFI"), amenities.contains("SECURITY"),
                amenities.contains("PARKING"), amenities.contains("PETS"));
    }

    // ---------- Helpers ----------

    private Content page(Http.Request request, String role, Form form, String error, String notice, boolean locked) {
        return views.html.listingForm.render(role, form, error, notice, locked, request);
    }

    private CompletionStage<OwnerApi.Reply> call(Ctx c, String method, String path, JsonNode body) {
        return api.call(c.access, c.refresh, method, path, body).thenApply(reply -> {
            c.take(reply);
            return reply;
        });
    }

    /** Saves renewed tokens to the session on the page we send back. */
    private static Result finish(Ctx c, Http.Request request, Result result) {
        if (!c.renewed) return result;
        Map<String, String> session = new HashMap<>(request.session().data());
        session.put("accessToken", c.access);
        session.put("refreshToken", c.refresh);
        return result.withSession(session);
    }

    private static Result gate(Http.Request request) {
        if (request.session().get("accessToken").isEmpty()) return redirect(LOGIN);
        if (!OWNERS.contains(role(request))) return redirect("/dashboard");
        return null;
    }

    private static String role(Http.Request request) {
        return request.session().get("role").orElse("");
    }

    private static String owner(String role) {
        return "/api/v1/" + role.toLowerCase(Locale.ROOT);
    }

    private static boolean isSafeId(String id) {
        return id != null && id.matches("[A-Za-z0-9-]{1,64}");
    }

    /** Digits only: no minus sign, no decimals. Returns null when it isn't a whole number. */
    private static Long number(String value) {
        return value != null && value.matches("[0-9]{1,12}") ? Long.valueOf(value) : null;
    }

    private static String val(Map<String, String[]> data, String key) {
        if (data == null || !data.containsKey(key) || data.get(key).length == 0 || data.get(key)[0] == null) return "";
        return data.get(key)[0].trim();
    }

    private static boolean on(Map<String, String[]> data, String key) {
        return "true".equals(val(data, key));
    }

    private static String str(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? "" : value.asText("");
    }

    private static boolean flag(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() && value.asBoolean(false);
    }

    private static CompletionStage<Result> done(Result result) {
        return CompletableFuture.completedFuture(result);
    }
}