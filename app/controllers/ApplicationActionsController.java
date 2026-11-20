package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

/** Owners decide on applications: review, approve, reject with a reason, hide and restore. */
public class ApplicationActionsController extends Controller {

    private static final Set<String> ACTIONS = Set.of("review", "approve", "reject", "hide", "unhide", "remark");

    private final AuthedApi api;

    @Inject
    public ApplicationActionsController(AuthedApi api) {
        this.api = api;
    }

    public CompletionStage<Result> act(Http.Request request, String id, String action) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());
        if (!id.matches("[A-Za-z0-9-]{1,64}")) return DashSupport.done(redirect("/dashboard/applications"));

        if (role.equals("RENTER")) {
            String source = field(request, "source");
            String kind = kind(source);
            if (kind == null) return DashSupport.done(redirect("/dashboard/applications"));
            if (action.equals("archive") || action.equals("restore") || action.equals("delete")) {
                ObjectNode change = Json.newObject();
                change.put("state", action.equals("archive") ? "ARCHIVED" : action.equals("restore") ? "ACTIVE" : "DELETED");
                String backTo = action.equals("restore") ? "/dashboard/applications?hidden=true" : "/dashboard/applications";
                return api.put(request, "/api/v1/renter/application-state/" + source + "/" + id, change).thenApply(r ->
                        finish(request, backTo, r.expired, r.ok(), r.json, r.accessToken, r.refreshToken));
            }
            if (!action.equals("withdraw")) return DashSupport.done(redirect("/dashboard/applications"));
            return api.delete(request, "/api/v1/renter/" + kind + "/" + id).thenApply(r ->
                    finish(request, "/dashboard/applications", r.expired, r.ok(), r.json, r.accessToken, r.refreshToken));
        }
        if (!ACTIONS.contains(action)) return DashSupport.done(redirect("/dashboard/applications"));

        String base = (role.equals("LANDLORD") ? "/api/v1/landlord" : "/api/v1/sublessor") + "/applications/" + id;
        String back = action.equals("unhide") ? "/dashboard/applications?hidden=true" : "/dashboard/applications";

        if (action.equals("hide") || action.equals("unhide")) {
            ObjectNode body = Json.newObject();
            body.put("hidden", action.equals("hide"));
            return api.put(request, base + "/hidden", body).thenApply(r ->
                    finish(request, back, r.expired, r.ok(), r.json, r.accessToken, r.refreshToken));
        }

        if (action.equals("remark")) {
            String text = field(request, "remark");
            if (text.length() > 500) return DashSupport.done(redirect(back).flashing("ap", "failed"));
            ObjectNode note = Json.newObject();
            note.put("remark", text);
            return api.put(request, base + "/remark", note).thenApply(r ->
                    finish(request, back, r.expired, r.ok(), r.json, r.accessToken, r.refreshToken));
        }

        ObjectNode body = null;
        if (action.equals("reject")) {
            String reason = field(request, "reason");
            if (reason.isEmpty() || reason.length() > 500) {
                return DashSupport.done(redirect(back).flashing("ap", "need-reason"));
            }
            body = Json.newObject();
            body.put("reason", reason);
        }
        final ObjectNode payload = body;
        // Like the app, a new application can be approved or rejected straight away.
        // The server wants it under review first, so move it there before the decision.
        if (action.equals("approve") || action.equals("reject")) {
            return api.post(request, base + "/review", null).thenCompose(first ->
                    api.post(request, base + "/" + action, payload).thenApply(r ->
                            finish(request, back, r.expired, r.ok(), r.json,
                                    r.accessToken != null ? r.accessToken : first.accessToken,
                                    r.refreshToken != null ? r.refreshToken : first.refreshToken)));
        }
        return api.post(request, base + "/" + action, body).thenApply(r ->
                finish(request, back, r.expired, r.ok(), r.json, r.accessToken, r.refreshToken));
    }

    /** Renters read what the owner wrote about their application. */
    public CompletionStage<Result> hostRemark(Http.Request request, String id, String source) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());
        String kind = kind(source);
        if (!role.equals("RENTER") || kind == null || !id.matches("[A-Za-z0-9-]{1,64}")) {
            return DashSupport.done(redirect("/dashboard/applications"));
        }
        return api.getAll(request, java.util.List.of("/api/v1/renter/" + kind + "/" + id + "/remark")).thenApply(batch -> {
            if (batch.expired) return DashSupport.toSignIn();
            JsonNode json = batch.json.get(0);
            String text = json == null ? "" : json.path("remark").asText("");
            if (text.length() > 500) text = text.substring(0, 500);
            Map<String, String> flash = new HashMap<>();
            flash.put("hrid", id);
            flash.put("hr", json == null ? "unavailable" : (text.isEmpty() ? "none" : text));
            Result result = redirect("/dashboard/applications").flashing(flash);
            return DashSupport.applyTokens(result, batch, request);
        });
    }

    /** Opens the conversation that belongs to one application, for the renter and for the owner. */
    public CompletionStage<Result> chat(Http.Request request, String id) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());
        if (!id.matches("[A-Za-z0-9-]{1,64}")) return DashSupport.done(redirect("/dashboard/applications"));

        boolean renter = role.equals("RENTER");
        boolean sharedHome = renter
                ? "SUBLESSOR".equals(request.queryString("source").orElse(""))
                : role.equals("SUBLESSOR");
        // Renters look through their threads. Owners ask for the one conversation of the application.
        List<String> paths = renter
                ? List.of(sharedHome ? "/api/v1/renter/roommate-chat/threads" : "/api/v1/renter/rental-chat/threads")
                : List.of("/api/v1/" + role.toLowerCase(java.util.Locale.ROOT) + "/applications/" + id + "/conversation");
        String slug = sharedHome ? "roommate" : "home";
        String linkField = sharedHome ? "roommateInterestId" : "rentalApplicationId";

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) return redirect("/get-started?mode=login").withNewSession();

            String threadId = "";
            JsonNode json = batch.json.get(0);
            if (json != null) {
                if (renter) {
                    JsonNode list = json.isArray() ? json
                            : (json.has("items") ? json.path("items") : json.path("content"));
                    if (list.isArray()) {
                        for (JsonNode t : list) {
                            if (id.equals(t.path(linkField).asText(""))) { threadId = t.path("id").asText(""); break; }
                        }
                    }
                } else {
                    threadId = json.path("id").asText("");
                }
            }

            Result next = threadId.matches("[A-Za-z0-9-]{1,64}")
                    ? redirect("/dashboard/messages/" + slug + "/" + threadId)
                    : redirect("/dashboard/applications").flashing("ap", "no-chat");
            if (batch.refreshed()) {
                Map<String, String> session = new HashMap<>(request.session().data());
                session.put("accessToken", batch.accessToken);
                session.put("refreshToken", batch.refreshToken);
                return next.withSession(session);
            }
            return next;
        });
    }

    private static String kind(String source) {
        if ("LANDLORD".equals(source)) return "applications";
        if ("SUBLESSOR".equals(source)) return "sublessor-applications";
        return null;
    }

    private static String field(Http.Request request, String name) {
        Map<String, String[]> form = request.body().asFormUrlEncoded();
        if (form == null || form.get(name) == null || form.get(name).length == 0) return "";
        return form.get(name)[0].trim();
    }

    static Result finish(Http.Request request, String back, boolean expired, boolean ok, JsonNode json,
                         String access, String refresh) {
        if (expired) return DashSupport.toSignIn();
        Result result;
        if (ok) {
            result = redirect(back).flashing("ap", "done");
        } else {
            String message = json == null ? "" : json.path("message").asText(json.path("detail").asText(""));
            if (message.length() > 200) message = message.substring(0, 200);
            Map<String, String> flash = new HashMap<>();
            flash.put("ap", "failed");
            if (!message.isEmpty()) flash.put("apmsg", message);
            result = redirect(back).flashing(flash);
        }
        if (access != null) {
            Map<String, String> session = new HashMap<>(request.session().data());
            session.put("accessToken", access);
            session.put("refreshToken", refresh);
            result = result.withSession(session);
        }
        return result;
    }
}
