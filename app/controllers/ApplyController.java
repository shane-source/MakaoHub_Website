package controllers;

import jakarta.inject.Inject;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.OwnerApi;

public class ApplyController extends Controller {

    private static final Logger LOG = LoggerFactory.getLogger(ApplyController.class);
    private static final String LOGIN = "/get-started?mode=login";

    private final OwnerApi api;

    @Inject
    public ApplyController(OwnerApi api) {
        this.api = api;
    }

    /** Sends the renter's application. The backend decides whether this renter may apply to this home. */
    public CompletionStage<Result> apply(Http.Request request, String source, String id) {
        if (request.session().get("accessToken").isEmpty()) return completed(redirect(LOGIN));
        if (!"RENTER".equals(request.session().get("role").orElse(""))) return completed(redirect("/dashboard"));

        boolean landlord = source.equals("LANDLORD");
        if ((!landlord && !source.equals("SUBLESSOR")) || !id.matches("[A-Za-z0-9-]{1,64}")) {
            return completed(redirect("/dashboard/homes"));
        }

        String kind = landlord ? "applications" : "sublessor-applications";
        String page = "/dashboard/homes/" + source + "/" + id;
        String access = request.session().get("accessToken").orElse("");
        String refresh = request.session().get("refreshToken").orElse("");

        String moveIn = field(request, "moveInDate");
        String message = field(request, "message");
        LocalDate date = parse(moveIn);
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Nairobi"));
        if (date == null || date.isBefore(today) || date.isAfter(today.plusYears(1))) {
            return completed(redirect(page).flashing(java.util.Map.of("hm", "apply-failed", "hmMsg", "Choose the date you want to move in. It must be within the next year.")));
        }
        if (message.length() > 500) message = message.substring(0, 500);
        ObjectNode body = Json.newObject();
        body.put("moveInDate", date.toString());
        if (!message.isEmpty()) body.put("message", message);

        return api.call(access, refresh, "POST", "/api/v1/renter/" + kind + "/listing/" + id, body).thenApply(reply -> {
            if (reply.expired()) return redirect(LOGIN).withNewSession();

            Map<String, String> flash = new HashMap<>();
            if (reply.ok()) {
                flash.put("hm", "applied");
            } else {
                LOG.warn("apply failed: status={} body={}", reply.status(), reply.json());
                flash.put("hm", "apply-failed");
                flash.put("hmMsg", reason(reply));
            }

            Result back = redirect(page).flashing(flash);
            if (reply.access() != null) {
                Map<String, String> session = new HashMap<>(request.session().data());
                session.put("accessToken", reply.access());
                session.put("refreshToken", reply.refresh());
                return back.withSession(session);
            }
            return back;
        });
    }

    private static String field(Http.Request request, String name) {
        Map<String, String[]> form = request.body().asFormUrlEncoded();
        if (form == null || !form.containsKey(name) || form.get(name).length == 0) return "";
        return form.get(name)[0].trim();
    }

    private static LocalDate parse(String value) {
        try {
            return LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    /** A message that is safe to show. The backend's own words are used where it gives some. */
    private static String reason(OwnerApi.Reply reply) {
        if (reply.status() == 409) {
            return "You've already applied to this home, or it isn't taking applications right now.";
        }
        if (reply.status() == 404) {
            return "This home is no longer available.";
        }
        return reply.message();
    }

    private static CompletionStage<Result> completed(Result result) {
        return CompletableFuture.completedFuture(result);
    }
}