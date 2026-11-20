package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.OwnerApi;

public class ListingLocationController extends Controller {

    private static final Logger LOG = LoggerFactory.getLogger(ListingLocationController.class);

    private static final Set<String> OWNERS = Set.of("LANDLORD", "SUBLESSOR");
    private static final String LOGIN = "/get-started?mode=login";

    // Kenya's 47 counties, the same list as the Android app. The chosen county is the public area label.
    public static final List<String> COUNTIES = List.of(
            "Baringo", "Bomet", "Bungoma", "Busia", "Elgeyo/Marakwet", "Embu", "Garissa", "Homa Bay",
            "Isiolo", "Kajiado", "Kakamega", "Kericho", "Kiambu", "Kilifi", "Kirinyaga", "Kisii",
            "Kisumu", "Kitui", "Kwale", "Laikipia", "Lamu", "Machakos", "Makueni", "Mandera",
            "Marsabit", "Meru", "Migori", "Mombasa", "Murang'a", "Nairobi City", "Nakuru", "Nandi",
            "Narok", "Nyamira", "Nyandarua", "Nyeri", "Samburu", "Siaya", "Taita/Taveta", "Tana River",
            "Tharaka-Nithi", "Trans Nzoia", "Turkana", "Uasin Gishu", "Vihiga", "Wajir", "West Pokot");

    // The supported map area, the same limits as the Android app.
    private static final BigDecimal MIN_LAT = new BigDecimal("-5");
    private static final BigDecimal MAX_LAT = new BigDecimal("5.5");
    private static final BigDecimal MIN_LNG = new BigDecimal("33");
    private static final BigDecimal MAX_LNG = new BigDecimal("42.5");

    public record LocationView(
            String id,
            String title,
            String status,
            boolean editable,
            String area,
            String lat,
            String lng,
            boolean hasPin,
            boolean loadFailed,
            List<String> counties,
            String mapsKey) {}

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
    private final String mapsKey;

    @Inject
    public ListingLocationController(OwnerApi api, com.typesafe.config.Config config) {
        this.api = api;
        this.mapsKey = config.hasPath("makao.google.mapsKey") ? config.getString("makao.google.mapsKey").trim() : "";
    }

    // ---------- The page ----------

    public CompletionStage<Result> page(Http.Request request, String id) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);
        if (!isSafeId(id)) return done(redirect("/dashboard/listings"));

        String role = role(request);
        Ctx c = new Ctx(request);
        String all = owner(role) + "/listings";

        return call(c, "GET", all, null).thenCompose(list -> {
            if (list.expired()) return done(redirect(LOGIN).withNewSession());

            JsonNode found = find(list.json(), id);
            if (found == null) return done(finish(c, request, redirect("/dashboard/listings")));

            return call(c, "GET", all + "/" + id + "/location", null).thenApply(location -> {
                if (location.expired()) return redirect(LOGIN).withNewSession();

                // A listing with no location yet answers 404. That is normal, not a failure.
                boolean none = location.status() == 404;
                boolean failed = !location.ok() && !none;

                String area = location.ok() ? location.text("areaLabel") : "";
                String lat = location.ok() ? location.text("latitude") : "";
                String lng = location.ok() ? location.text("longitude") : "";
                boolean hasPin = !lat.isEmpty() && !lng.isEmpty();

                String status = found.path("status").asText("");
                LocationView view = new LocationView(
                        id,
                        found.path("title").asText("Your listing"),
                        status,
                        status.equals("DRAFT"),
                        COUNTIES.contains(area) ? area : "",
                        lat,
                        lng,
                        hasPin,
                        failed,
                        COUNTIES,
                        mapsKey);
                return finish(c, request, ok(views.html.listingLocation.render(role, view, request)));
            });
        });
    }

    // ---------- Saving ----------

    public CompletionStage<Result> save(Http.Request request, String id) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);
        if (!isSafeId(id)) return done(redirect("/dashboard/listings"));

        String role = role(request);
        String page = "/dashboard/listings/" + id + "/location";

        Map<String, String[]> form = request.body().asFormUrlEncoded();
        String area = field(form, "area");
        BigDecimal lat = decimal(field(form, "lat"));
        BigDecimal lng = decimal(field(form, "lng"));

        // The browser already checked these. We check again because anyone can bypass the browser.
        if (!COUNTIES.contains(area) || lat == null || lng == null
                || lat.compareTo(MIN_LAT) < 0 || lat.compareTo(MAX_LAT) > 0
                || lng.compareTo(MIN_LNG) < 0 || lng.compareTo(MAX_LNG) > 0) {
            return done(redirect(page).flashing("locErr",
                    "Choose a county and select a property pin inside the supported Kenya map area."));
        }

        ObjectNode body = Json.newObject();
        body.put("areaLabel", area);
        body.put("latitude", lat.setScale(7, RoundingMode.HALF_UP));
        body.put("longitude", lng.setScale(7, RoundingMode.HALF_UP));

        Ctx c = new Ctx(request);
        return call(c, "PUT", owner(role) + "/listings/" + id + "/location", body).thenApply(reply -> {
            if (reply.expired()) return redirect(LOGIN).withNewSession();
            if (!reply.ok()) {
                LOG.warn("location save failed: status={} body={}", reply.status(), reply.json());
                return finish(c, request, redirect(page).flashing("locErr", reply.message()));
            }
            return finish(c, request, redirect(page).flashing("locNote", "Private location saved."));
        });
    }

    // ---------- Helpers ----------

    private static BigDecimal decimal(String value) {
        if (!value.matches("-?[0-9]{1,2}(\\.[0-9]{1,10})?")) return null;
        return new BigDecimal(value);
    }

    private static String field(Map<String, String[]> form, String name) {
        if (form == null || !form.containsKey(name) || form.get(name).length == 0 || form.get(name)[0] == null) return "";
        return form.get(name)[0].trim();
    }

    private static JsonNode find(JsonNode json, String id) {
        if (json == null) return null;
        JsonNode list = json.isArray() ? json : json.path("items");
        if (!list.isArray()) return null;
        for (JsonNode n : list) {
            if (id.equals(n.path("id").asText(""))) return n;
        }
        return null;
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

    private static <T> CompletionStage<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }
}