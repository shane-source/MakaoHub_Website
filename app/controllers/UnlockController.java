package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

/**
 * Paid access to one home's host phone, video tour or exact location, like the app's "Secure access".
 * The backend decides who has paid; this page only asks and shows what the backend allows.
 */
public class UnlockController extends Controller {

    private static final String LOGIN = "/get-started?mode=login";

    /** What one kind of access looks like on the page. */
    public record Kind(String slug, String code, String path, String title, String lockedTitle,
                       String lockedSub, String lockedBody) {}

    private static final Map<String, Kind> KINDS = Map.of(
            "phone", new Kind("phone", "PHONE_UNLOCK", "/phone",
                    "Your unlocked contact", "Contact is protected",
                    "Host contact · masked until you unlock this listing.",
                    "Phone number\nShown only after a Paid phone entitlement for this listing."),
            "video", new Kind("video", "VIDEO_UNLOCK", "/media/video",
                    "Your video tour", "Take a closer look",
                    "Video tour locked",
                    "Video access\nUnlocked only for this listing after confirmed payment."),
            "location", new Kind("location", "EXACT_LOCATION_UNLOCK", "/location/exact",
                    "Your unlocked location", "Get to know the neighbourhood",
                    "Unlock the exact location of this home.",
                    "Exact map pin\nAvailable only after a Paid location entitlement."));

    /** What the page shows. Either the unlocked content or the locked offer. */
    public record AccessView(
            Kind kind,
            String source,
            String id,
            boolean unlocked,
            String ownerName,
            String phone,
            String areaLabel,
            String latitude,
            String longitude,
            List<String> videos,
            String price,
            boolean priceUnavailable,
            String key,
            String notice,
            boolean loadFailed) {}

    private final AuthedApi api;

    @Inject
    public UnlockController(AuthedApi api) {
        this.api = api;
    }

    // ---------- The access page ----------

    public CompletionStage<Result> page(Http.Request request, String source, String id, String type) {
        Result blocked = guard(request);
        if (blocked != null) return CompletableFuture.completedFuture(blocked);
        Kind kind = KINDS.get(type);
        if (kind == null || !id.matches("[A-Za-z0-9-]{1,64}") || !source.matches("[A-Za-z0-9_-]{1,30}")) {
            return CompletableFuture.completedFuture(redirect("/dashboard/homes"));
        }

        String notice = request.flash().get("unlock").orElse("");

        return api.getWithStatus(request, "/api/v1/renter/listings/" + id + kind.path()).thenCompose(read -> {
            if (read.expired) {
                return CompletableFuture.completedFuture(redirect(LOGIN).withNewSession());
            }

            if (read.status == 200 && read.json != null) {
                return CompletableFuture.completedFuture(withFreshSession(request, read,
                        ok(views.html.unlock.render(unlockedView(kind, source, id, read.json, notice), request))));
            }

            if (read.status != 403) {
                return CompletableFuture.completedFuture(withFreshSession(request, read,
                        ok(views.html.unlock.render(emptyView(kind, source, id, "", false, true, notice), request))));
            }

            // Not paid yet: show the price and the Continue button.
            return api.getWithStatus(request, "/api/v1/payments/prices/" + kind.code()).thenApply(priceResult -> {
                if (priceResult.expired) {
                    return redirect(LOGIN).withNewSession();
                }
                String price = "";
                boolean unavailable = true;
                if (priceResult.status == 200 && priceResult.json != null
                        && priceResult.json.has("amountMinor")) {
                    price = money(priceResult.json.path("amountMinor").asLong(),
                            priceResult.json.path("currency").asText("KES"));
                    unavailable = false;
                }
                return withFreshSession(request, priceResult,
                        ok(views.html.unlock.render(
                                emptyView(kind, source, id, price, unavailable, false, notice), request)));
            });
        });
    }

    // ---------- Pay ----------

    public CompletionStage<Result> start(Http.Request request, String source, String id, String type) {
        Result blocked = guard(request);
        if (blocked != null) return CompletableFuture.completedFuture(blocked);
        Kind kind = KINDS.get(type);
        String back = "/dashboard/homes/" + source + "/" + id + "/access/" + type;
        if (kind == null || !id.matches("[A-Za-z0-9-]{1,64}") || !source.matches("[A-Za-z0-9_-]{1,30}")) {
            return CompletableFuture.completedFuture(redirect("/dashboard/homes"));
        }

        Map<String, String[]> form = request.body().asFormUrlEncoded();
        String key = form == null || form.get("key") == null || form.get("key").length == 0
                ? "" : form.get("key")[0].trim();
        if (!key.matches("[A-Za-z0-9-]{8,64}")) {
            return CompletableFuture.completedFuture(redirect(back).flashing("unlock", "failed"));
        }

        ObjectNode body = Json.newObject();
        body.put("paymentType", kind.code());
        body.put("listingId", id);

        return api.postKeyed(request, "/api/v1/payments/intents", body, key).thenApply(result -> {
            if (result.expired) {
                return redirect(LOGIN).withNewSession();
            }
            String url = result.json == null ? "" : result.json.path("authorizationUrl").asText("");
            Result next = (result.ok() && url.startsWith("https://"))
                    ? redirect(url)
                    : redirect(back).flashing("unlock", "failed");
            if (result.refreshed()) {
                Map<String, String> session = new HashMap<>(request.session().data());
                session.put("accessToken", result.accessToken);
                session.put("refreshToken", result.refreshToken);
                return next.withSession(session);
            }
            return next;
        });
    }

    // ---------- Helpers ----------

    private static AccessView unlockedView(Kind kind, String source, String id, JsonNode json, String notice) {
        String ownerName = json.path("ownerName").asText("");
        String phone = json.path("phoneNumber").asText("");
        String area = json.path("areaLabel").asText("");
        String lat = json.has("latitude") ? json.path("latitude").asText("") : "";
        String lng = json.has("longitude") ? json.path("longitude").asText("") : "";

        List<String> videos = new ArrayList<>();
        if (json.isArray()) {
            for (JsonNode m : json) {
                String url = m.path("signedReadUrl").asText("");
                // Only secure video addresses are shown.
                if (url.startsWith("https://")) videos.add(url);
            }
        }
        return new AccessView(kind, source, id, true, ownerName, phone, area, lat, lng, videos,
                "", false, "", notice, false);
    }

    private static AccessView emptyView(Kind kind, String source, String id, String price,
                                        boolean priceUnavailable, boolean loadFailed, String notice) {
        return new AccessView(kind, source, id, false, "", "", "", "", "", List.of(), price,
                priceUnavailable, UUID.randomUUID().toString(), notice, loadFailed);
    }

    /** Prices arrive in minor units, so 49900 means KSh 499. */
    private static String money(long minor, String currency) {
        String code = currency.equalsIgnoreCase("KES") ? "KSh" : currency.toUpperCase(Locale.ROOT);
        long major = minor / 100;
        long cents = minor % 100;
        return code + " " + String.format(Locale.UK, "%,d", major) + (cents == 0 ? "" : String.format(".%02d", cents));
    }

    private static Result guard(Http.Request request) {
        if (!request.session().get("accessToken").isPresent()) return redirect(LOGIN);
        if (!"RENTER".equals(request.session().get("role").orElse(""))) return redirect("/dashboard");
        return null;
    }

    private static Result withFreshSession(Http.Request request, AuthedApi.WriteResult result, Result page) {
        if (!result.refreshed()) return page;
        Map<String, String> session = new HashMap<>(request.session().data());
        session.put("accessToken", result.accessToken);
        session.put("refreshToken", result.refreshToken);
        return page.withSession(session);
    }
}
