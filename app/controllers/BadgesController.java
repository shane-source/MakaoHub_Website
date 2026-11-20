package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

/** The two small numbers on the Messages and Applications tabs, like the app's badges. */
public class BadgesController extends Controller {

    private final AuthedApi api;

    @Inject
    public BadgesController(AuthedApi api) {
        this.api = api;
    }

    public CompletionStage<Result> counts(Http.Request request) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(unauthorized(Json.newObject()));

        List<String> paths = new ArrayList<>();
        String owner = "/api/v1/" + role.toLowerCase(Locale.ROOT);
        if (role.equals("RENTER")) {
            paths.add("/api/v1/renter/rental-chat/threads");
            paths.add("/api/v1/renter/roommate-chat/threads");
            paths.add("/api/v1/renter/applications");
            paths.add("/api/v1/renter/sublessor-applications");
        } else {
            paths.add(owner + (role.equals("LANDLORD") ? "/rental-chat/threads" : "/roommate-chat/threads"));
            paths.add(owner + "/applications");
        }

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) return unauthorized(Json.newObject());

            long messages = 0;
            long applications = 0;
            ArrayNode threads = Json.newArray();
            ArrayNode apps = Json.newArray();
            int chatCount = role.equals("RENTER") ? 2 : 1;
            for (int i = 0; i < paths.size(); i++) {
                JsonNode json = batch.json.get(i);
                if (json == null) continue;
                JsonNode list = json.isArray() ? json : (json.has("content") ? json.path("content") : json.path("items"));
                if (!list.isArray()) continue;
                if (i < chatCount) {
                    for (JsonNode thread : list) {
                        long unread = thread.path("unreadCount").asLong(0);
                        messages += unread;
                        String id = thread.path("id").asText("");
                        if (!id.isEmpty()) {
                            ObjectNode t = Json.newObject();
                            t.put("id", id);
                            t.put("unread", unread);
                            t.put("at", thread.path("lastMessageAt").asText(""));
                            threads.add(t);
                        }
                    }
                } else if (role.equals("RENTER")) {
                    applications += list.size();
                    for (JsonNode a : list) addKey(apps, a);
                } else {
                    for (JsonNode a : list) {
                        if (a.path("status").asText("").equals("SUBMITTED")) {
                            applications++;
                            addKey(apps, a);
                        }
                    }
                }
            }

            ObjectNode out = Json.newObject();
            out.put("messages", messages);
            out.put("applications", applications);
            // The page keeps what the person has already opened and subtracts it, so a number clears once looked at.
            out.put("who", role + "|" + request.session().get("fullName").orElse(""));
            out.set("threads", threads);
            out.set("apps", apps);
            return DashSupport.applyTokens(ok(out).withHeader("Cache-Control", "no-store"), batch, request);
        });
    }

    /** One entry per application, changing when its status changes, so a new decision shows up again. */
    private static void addKey(ArrayNode apps, JsonNode a) {
        String id = a.path("id").asText("");
        if (!id.isEmpty()) apps.add(id + ":" + a.path("status").asText(""));
    }
}
