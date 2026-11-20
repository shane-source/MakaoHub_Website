package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
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
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.OwnerApi;

public class ListingReviewController extends Controller {

    private static final Logger LOG = LoggerFactory.getLogger(ListingReviewController.class);

    private static final Set<String> OWNERS = Set.of("LANDLORD", "SUBLESSOR");
    private static final String LOGIN = "/get-started?mode=login";

    /** One requirement. state is "good", "warn" or "bad". href is where to fix it, or empty. */
    public record Check(String title, String detail, String state, String href) {}

    public record ReviewView(
            String id,
            String title,
            String status,
            List<Check> checks,
            String stage,
            String reason,
            boolean canSubmit,
            boolean canRevise,
            boolean canPublish,
            boolean canResume,
            boolean canPause) {}

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
    public ListingReviewController(OwnerApi api) {
        this.api = api;
    }

    // ---------- The page ----------

    public CompletionStage<Result> page(Http.Request request, String id) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);
        if (!isSafeId(id)) return done(redirect("/dashboard/listings"));

        String role = role(request);
        Ctx c = new Ctx(request);
        String all = owner(role) + "/listings";
        String one = all + "/" + id;

        return call(c, "GET", all, null).thenCompose(list -> {
            if (list.expired()) return done(redirect(LOGIN).withNewSession());
            JsonNode found = find(list.json(), id);
            if (found == null) return done(finish(c, request, redirect("/dashboard/listings")));

            return call(c, "GET", owner(role) + "/verification", null).thenCompose(verification -> {
                if (verification.expired()) return done(redirect(LOGIN).withNewSession());

                return call(c, "GET", one + "/media", null).thenCompose(media -> {
                    if (media.expired()) return done(redirect(LOGIN).withNewSession());

                    return call(c, "GET", one + "/location", null).thenCompose(location -> {
                        if (location.expired()) return done(redirect(LOGIN).withNewSession());

                        return call(c, "GET", one + "/review", null).thenApply(review -> {
                            if (review.expired()) return redirect(LOGIN).withNewSession();

                            boolean verified = verification.ok() && verification.text("status").equals("VERIFIED");
                            boolean hasPhoto = false;
                            for (JsonNode m : items(media.json())) {
                                if (m.path("mediaType").asText("").equals("IMAGE") && m.path("confirmed").asBoolean(false)) {
                                    hasPhoto = true;
                                    break;
                                }
                            }
                            boolean hasLocation = location.ok();

                            List<Check> checks = new ArrayList<>();
                            checks.add(verified
                                    ? new Check("Verified account", "Your verification is complete.", "good", "")
                                    : new Check("Verification required", "Complete your verification before publishing.", "warn", "/dashboard/profile"));
                            checks.add(hasPhoto
                                    ? new Check("Photos", "At least one photo is confirmed.", "good", "")
                                    : new Check("Photos required", "Upload and confirm at least one photo.", "bad", "/dashboard/listings/" + id + "/photos"));
                            checks.add(hasLocation
                                    ? new Check("Private location", "The exact pin and public area are saved.", "good", "")
                                    : new Check("Private location", "Save the exact pin and the public area.", "warn", "/dashboard/listings/" + id + "/location"));

                            String status = found.path("status").asText("");
                            String moderation = review.ok() ? review.text("moderation_status") : "";
                            boolean submitted = review.ok() && !review.text("moderation_submitted_at").isEmpty();
                            String reason = review.ok() ? review.text("moderation_reason") : "";

                            String stage;
                            if (status.equals("PUBLISHED")) stage = "PUBLISHED";
                            else if (!review.ok()) stage = "UNKNOWN";
                            else if (moderation.equals("PENDING") && !submitted) stage = "NOT_SUBMITTED";
                            else if (moderation.equals("PENDING")) stage = "IN_REVIEW";
                            else if (moderation.equals("REJECTED")) stage = "CHANGES";
                            else if (moderation.equals("APPROVED")) stage = "APPROVED";
                            else stage = "UNKNOWN";

                            ReviewView view = new ReviewView(
                                    id,
                                    found.path("title").asText("Your listing"),
                                    status,
                                    checks,
                                    stage,
                                    reason,
                                    stage.equals("NOT_SUBMITTED") && verified && hasPhoto && hasLocation,
                                    stage.equals("CHANGES"),
                                    stage.equals("APPROVED") && status.equals("DRAFT"),
                                    stage.equals("APPROVED") && status.equals("PAUSED"),
                                    stage.equals("PUBLISHED"));

                            return finish(c, request, ok(views.html.listingReview.render(role, view, request)));
                        });
                    });
                });
            });
        });
    }

    // ---------- The buttons ----------

    public CompletionStage<Result> act(Http.Request request, String id, String action) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);
        if (!isSafeId(id)) return done(redirect("/dashboard/listings"));

        String path;
        String note;
        switch (action) {
            case "submit":  path = "/submit-review"; note = "Listing submitted for staff review."; break;
            case "revise":  path = "/revise-review"; note = ""; break;
            case "publish": path = "/publish"; note = "Published. Exact coordinates stay protected by the server."; break;
            case "pause":   path = "/pause"; note = "Listing paused. It is hidden from discovery."; break;
            case "resume":  path = "/resume"; note = "Published listing resumed."; break;
            case "archive": path = "/archive"; note = "Listing removed."; break;
            default: return done(redirect("/dashboard/listings/" + id + "/review"));
        }

        String role = role(request);
        String page = "/dashboard/listings/" + id + "/review";
        Ctx c = new Ctx(request);

        return call(c, "POST", owner(role) + "/listings/" + id + path, null).thenApply(reply -> {
            if (reply.expired()) return redirect(LOGIN).withNewSession();

            if (!reply.ok()) {
                LOG.warn("listing {} failed: status={} body={}", action, reply.status(), reply.json());
                String message = reply.status() == 409
                        ? "That step isn't available right now. Check the status below, then try again."
                        : reply.message();
                return finish(c, request, redirect(page).flashing("revErr", message));
            }

            if (action.equals("archive")) {
                return finish(c, request, redirect("/dashboard/listings").flashing("ls", "removed"));
            }

            // After "Revise", the draft is editable again, as in the app.
            if (action.equals("revise")) {
                return finish(c, request, redirect("/dashboard/listings/" + id + "/edit").flashing("listing", "revise"));
            }
            return finish(c, request, redirect(page).flashing("revNote", note));
        });
    }

    // ---------- Helpers ----------

    private static JsonNode find(JsonNode json, String id) {
        for (JsonNode n : items(json)) {
            if (id.equals(n.path("id").asText(""))) return n;
        }
        return null;
    }

    private static List<JsonNode> items(JsonNode json) {
        List<JsonNode> out = new ArrayList<>();
        if (json == null) return out;
        JsonNode list = json.isArray() ? json : (json.has("content") ? json.path("content") : json.path("items"));
        if (list.isArray()) list.forEach(out::add);
        return out;
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