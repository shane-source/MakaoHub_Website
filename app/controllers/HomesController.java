package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class HomesController extends Controller {

    private static final Logger LOG = LoggerFactory.getLogger(HomesController.class);

    private static final String LOGIN = "/get-started?mode=login";
    private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9_-]{1,30}");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private static final DateTimeFormatter DAY_PLAIN = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_ZONED = DAY_PLAIN.withZone(ZoneId.of("Africa/Nairobi"));

    /** One home in the list. */
    public record Card(String title, String meta, String rent, boolean sponsored, boolean furnished, String href, long rentValue, String area) {}

    public record Filters(String area, String min, String max, String source, String bedrooms,
                          boolean furnished, String propertyType, String availableBy, String error) {
        /** The query string that keeps the filters while moving between pages. */
        public String keep() {
            StringBuilder b = new StringBuilder();
            add(b, "area", area); add(b, "min", min); add(b, "max", max); add(b, "source", source);
            add(b, "bedrooms", bedrooms); add(b, "propertyType", propertyType); add(b, "availableBy", availableBy);
            if (furnished) add(b, "furnished", "true");
            return b.toString();
        }

        /** The same filters, with the names the backend expects. */
        public String backend() {
            StringBuilder b = new StringBuilder();
            add(b, "area", area); add(b, "minRentKes", min); add(b, "maxRentKes", max); add(b, "source", source);
            add(b, "minBedrooms", bedrooms); add(b, "propertyType", propertyType); add(b, "availableBy", availableBy);
            if (furnished) add(b, "furnished", "true");
            return b.toString();
        }

        public boolean any() { return !keep().isEmpty(); }

        private static void add(StringBuilder b, String key, String value) {
            if (value == null || value.isEmpty()) return;
            b.append('&').append(key).append('=').append(java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    public record ListView(List<Card> cards, int page, boolean hasPrev, boolean hasNext, boolean loadFailed, Filters filters,
                           String sort, List<String> areas) {}

    private static final java.util.Set<String> PROPERTY_TYPES = java.util.Set.of(
            "APARTMENT", "HOUSE", "STUDIO", "BEDSITTER", "ROOM", "SPARE_ROOM", "SERVANT_QUARTER");

    /** Reads and checks the filters with the same rules as the app. */
    private static Filters readFilters(Http.Request request) {
        String area = clean(request.queryString("area").orElse(""), 150);
        String min = clean(request.queryString("min").orElse(""), 12);
        String max = clean(request.queryString("max").orElse(""), 12);
        String source = clean(request.queryString("source").orElse(""), 12);
        String bedrooms = clean(request.queryString("bedrooms").orElse(""), 3);
        String type = clean(request.queryString("propertyType").orElse(""), 20);
        String by = clean(request.queryString("availableBy").orElse(""), 10);
        boolean furnished = request.queryString("furnished").orElse("").equals("true");

        String error = null;
        if (!min.isEmpty() && !min.matches("\\d{1,12}")) error = "Enter a valid minimum rent.";
        else if (!max.isEmpty() && !max.matches("\\d{1,12}")) error = "Enter a valid maximum rent.";
        else if (!min.isEmpty() && !max.isEmpty() && Long.parseLong(min) > Long.parseLong(max)) error = "Maximum rent must not be below minimum.";
        else if (!bedrooms.isEmpty() && !bedrooms.matches("\\d{1,2}")) error = "Use a bedroom count from 0 to 20.";
        else if (!bedrooms.isEmpty() && Integer.parseInt(bedrooms) > 20) error = "Use a bedroom count from 0 to 20.";
        else if (!source.isEmpty() && !source.equals("LANDLORD") && !source.equals("SUBLESSOR")) error = "Choose a listing source.";
        else if (!type.isEmpty() && !PROPERTY_TYPES.contains(type)) error = "Choose a property type.";
        else if (!by.isEmpty()) {
            try { java.time.LocalDate.parse(by); } catch (Exception e) { error = "Use a move-in date in YYYY-MM-DD format."; }
        }
        if (error != null) {
            return new Filters(area, min, max, source, bedrooms, furnished, type, by, error);
        }
        return new Filters(area, min, max, source, bedrooms, furnished, type, by, null);
    }

    private static String clean(String value, int max) {
        String v = value == null ? "" : value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }

    /** One home in full. */
    public record DetailView(
            String source,
            String id,
            String title,
            String area,
            String rent,
            List<String> facts,
            String description,
            List<String> photos,
            boolean sponsored,
            boolean savedKnown,
            boolean saved,
            boolean loadFailed) {}

    private final AuthedApi api;

    @Inject
    public HomesController(AuthedApi api) {
        this.api = api;
    }

    // ---------- The list ----------

    public CompletionStage<Result> index(Http.Request request, Integer page) {
        Result blocked = guard(request);
        if (blocked != null) return completed(blocked);

        int p = Math.max(0, Math.min(page == null ? 0 : page, 1000));

        Filters filters = readFilters(request);
        StringBuilder query = new StringBuilder("/api/v1/renter/homes?page=" + p + "&saved=false");
        if (filters.error() == null) query.append(filters.backend());

        return api.getAll(request, List.of(query.toString())).thenApply(batch -> {
            if (batch.expired) return redirect(LOGIN).withNewSession();

            JsonNode json = batch.json.get(0);
            List<Card> cards = new ArrayList<>();
            for (JsonNode h : listOf(json)) {
                cards.add(card(h));
            }
            boolean hasNext = json != null && json.path("hasNext").asBoolean(false);

            // "Relevant" keeps the order from the backend. The price orders sort the homes on this page.
            String sort = request.queryString("sort").orElse("relevant");
            if (sort.equals("low")) cards.sort(Comparator.comparingLong(Card::rentValue));
            else if (sort.equals("high")) cards.sort(Comparator.comparingLong(Card::rentValue).reversed());
            else sort = "relevant";
            List<String> areas = new ArrayList<>();
            for (Card c : cards) {
                if (!c.area().isEmpty() && !areas.contains(c.area())) areas.add(c.area());
            }

            Result result = ok(views.html.homesBrowse.render(
                    "RENTER", new ListView(cards, p, p > 0, hasNext, json == null, filters, sort, areas), request));
            return withFreshSession(request, batch, result);
        });
    }

    // ---------- One home ----------

    public CompletionStage<Result> show(Http.Request request, String source, String id) {
        Result blocked = guard(request);
        if (blocked != null) return completed(blocked);
        if (!SOURCE.matcher(source).matches() || !ID.matcher(id).matches()) {
            return completed(redirect("/dashboard/homes"));
        }

        String base = "/api/v1/renter/homes/" + source + "/" + id;

        return api.getAll(request, List.of(base, base + "/photos")).thenApply(batch -> {
            if (batch.expired) return redirect(LOGIN).withNewSession();

            JsonNode h = batch.json.get(0);
            JsonNode photoJson = batch.json.get(1);

            List<String> photos = new ArrayList<>();
            List<JsonNode> media = new ArrayList<>(listOf(photoJson));
            media.sort(Comparator.comparingInt((JsonNode m) -> m.path("sortOrder").asInt(0)));
            for (JsonNode m : media) {
                String url = m.path("signedReadUrl").asText("");
                String type = m.path("contentType").asText("");
                // Only secure image addresses are shown.
                if (url.startsWith("https://") && (type.isEmpty() || type.startsWith("image/"))) {
                    photos.add(url);
                }
            }

            DetailView view;
            if (h == null) {
                view = new DetailView(source, id, "Home", "", "", List.of(), "", photos, false, false, false, true);
            } else {
                boolean savedKnown = h.has("saved");
                view = new DetailView(
                        source,
                        id,
                        h.path("title").asText("Home"),
                        h.path("areaLabel").asText(""),
                        kes(h.path("monthlyRentKes").asLong()) + " / month",
                        facts(h),
                        h.path("description").asText(""),
                        photos,
                        h.path("sponsored").asBoolean(false),
                        savedKnown,
                        savedKnown && h.path("saved").asBoolean(false),
                        false);
            }

            return withFreshSession(request, batch, ok(views.html.homeDetail.render("RENTER", view, request)));
        });
    }

    // ---------- Save or remove ----------

    public CompletionStage<Result> save(Http.Request request, String source, String id) {
        Result blocked = guard(request);
        if (blocked != null) return completed(blocked);
        if (!SOURCE.matcher(source).matches() || !ID.matcher(id).matches()) {
            return completed(redirect("/dashboard/homes"));
        }

        boolean remove = "remove".equals(field(request, "do"));
        String address = "/api/v1/renter/homes/" + source + "/" + id + "/save";
        String page = "/dashboard/homes/" + source + "/" + id;

        CompletionStage<AuthedApi.WriteResult> call = remove ? api.delete(request, address) : api.put(request, address, null);

        return call.thenApply(result -> {
            if (result.expired) return redirect(LOGIN).withNewSession();
            if (!result.ok()) {
                LOG.warn("save home failed: status={} body={}", result.status, result.json);
            }
            Result back = redirect(page).flashing("hm", result.ok() ? (remove ? "removed" : "saved") : "failed");
            if (result.refreshed()) {
                return back.withSession(sessionWith(request, result.accessToken, result.refreshToken));
            }
            return back;
        });
    }

    // ---------- Wording ----------

    private static Card card(JsonNode h) {
        List<String> parts = new ArrayList<>();
        String area = h.path("areaLabel").asText("");
        if (!area.isEmpty()) parts.add(area);
        parts.add(bedrooms(h));
        int baths = h.path("bathroomCount").asInt();
        parts.add(baths + (baths == 1 ? " bath" : " baths"));

        String source = h.path("source").asText("");
        String id = h.path("id").asText("");
        String href = (SOURCE.matcher(source).matches() && ID.matcher(id).matches())
                ? "/dashboard/homes/" + source + "/" + id : "";

        return new Card(
                h.path("title").asText("Home"),
                String.join(" · ", parts),
                kes(h.path("monthlyRentKes").asLong()) + " / month",
                h.path("sponsored").asBoolean(false),
                h.path("furnished").asBoolean(false),
                href,
                h.path("monthlyRentKes").asLong(),
                area);
    }

    private static List<String> facts(JsonNode h) {
        List<String> out = new ArrayList<>();
        String type = h.path("propertyType").asText("");
        if (!type.isEmpty()) out.add(pretty(type));
        out.add(bedrooms(h));
        int baths = h.path("bathroomCount").asInt();
        out.add(baths + (baths == 1 ? " bathroom" : " bathrooms"));
        out.add(h.path("furnished").asBoolean(false) ? "Furnished" : "Unfurnished");
        String from = date(h.path("availableFrom").asText(""));
        if (!from.isEmpty()) out.add("Available from " + from);
        return out;
    }

    private static String bedrooms(JsonNode h) {
        if (!h.hasNonNull("bedroomCount")) return "Shared space";
        int beds = h.path("bedroomCount").asInt();
        return beds + (beds == 1 ? " bedroom" : " bedrooms");
    }

    /** Rents arrive in whole shillings. */
    private static String kes(long value) {
        return "KSh " + String.format(Locale.UK, "%,d", value);
    }

    private static String date(String raw) {
        if (raw.isEmpty()) return "";
        try {
            return DAY_PLAIN.format(LocalDate.parse(raw));
        } catch (Exception e) {
            try {
                return DAY_ZONED.format(Instant.parse(raw));
            } catch (Exception e2) {
                return "";
            }
        }
    }

    private static String pretty(String code) {
        if (code.isEmpty()) return "";
        String text = code.toLowerCase(Locale.ROOT).replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    // ---------- Helpers ----------

    /** Only signed-in renters get in. Returns a redirect to send anyone else away, or null to carry on. */
    private static Result guard(Http.Request request) {
        if (!request.session().get("accessToken").isPresent()) return redirect(LOGIN);
        if (!"RENTER".equals(request.session().get("role").orElse(""))) return redirect("/dashboard");
        return null;
    }

    /** The backend may return a plain list or an object with an "items" or "content" list. */
    private static List<JsonNode> listOf(JsonNode json) {
        List<JsonNode> out = new ArrayList<>();
        if (json == null) return out;
        JsonNode list = json.isArray() ? json : (json.has("items") ? json.path("items") : json.path("content"));
        if (list.isArray()) list.forEach(out::add);
        return out;
    }

    private static String field(Http.Request request, String name) {
        Map<String, String[]> form = request.body().asFormUrlEncoded();
        if (form == null || !form.containsKey(name) || form.get(name).length == 0) return "";
        return form.get(name)[0].trim();
    }

    private static Result withFreshSession(Http.Request request, AuthedApi.Batch batch, Result result) {
        if (!batch.refreshed()) return result;
        return result.withSession(sessionWith(request, batch.accessToken, batch.refreshToken));
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