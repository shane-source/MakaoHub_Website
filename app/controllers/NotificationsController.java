package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class NotificationsController extends Controller {

    private static final Set<String> ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");

    /** One notification, already cleaned up for the page. */
    public record Note(String id, String title, String message, boolean unread) {}

    public record NotificationsView(List<Note> items, long unread, boolean loadFailed) {}

    private final AuthedApi api;

    @Inject
    public NotificationsController(AuthedApi api) {
        this.api = api;
    }

    // ---------- The list ----------

    public CompletionStage<Result> index(Http.Request request) {
        String role = roleOf(request);
        if (role.isEmpty()) {
            return CompletableFuture.completedFuture(redirect("/get-started?mode=login"));
        }

        return api.getAll(request, List.of("/api/v1/notifications")).thenApply(batch -> {
            if (batch.expired) {
                // The session is no longer valid: start again from sign in.
                return redirect("/get-started?mode=login").withNewSession();
            }

            JsonNode json = batch.json.get(0);
            List<Note> items = new ArrayList<>();

            // The backend may return a plain list or an object with an "items" list.
            JsonNode list = json == null ? null : (json.isArray() ? json : (json.has("content") ? json.path("content") : json.path("items")));
            if (list != null && list.isArray()) {
                for (JsonNode n : list) {
                    String message = n.path("message").asText("");
                    if (message.isEmpty()) message = n.path("body").asText("");
                    String title = n.path("title").asText("");
                    if (title.isEmpty()) {
                        title = message.isEmpty() ? "Notification" : message;
                        message = "";
                    }
                    boolean unread = !n.path("read").asBoolean(false);
                    items.add(new Note(n.path("id").asText(""), title, message, unread));
                }
            }

            long unread = items.stream().filter(Note::unread).count();
            if (json != null && json.has("unreadCount")) unread = json.path("unreadCount").asLong(unread);

            Result page = ok(views.html.notifications.render(role, new NotificationsView(items, unread, json == null), request));
            if (batch.refreshed()) {
                return page.withSession(sessionWith(request, batch.accessToken, batch.refreshToken));
            }
            return page;
        });
    }

    // ---------- Mark one notification as read ----------

    public CompletionStage<Result> markRead(Http.Request request, String id) {
        if (roleOf(request).isEmpty()) {
            return CompletableFuture.completedFuture(redirect("/get-started?mode=login"));
        }
        // The id goes into a backend address, so only plain ids are accepted.
        if (id == null || !id.matches("[A-Za-z0-9-]{1,64}")) {
            return CompletableFuture.completedFuture(redirect("/dashboard/notifications"));
        }

        return api.patch(request, "/api/v1/notifications/" + id + "/read").thenApply(result ->  {
            if (result.expired) {
                return redirect("/get-started?mode=login").withNewSession();
            }
            Result back = redirect("/dashboard/notifications");
            if (result.refreshed()) {
                return back.withSession(sessionWith(request, result.accessToken, result.refreshToken));
            }
            return back;
        });
    }

    // ---------- Helpers ----------

    private static String roleOf(Http.Request request) {
        boolean signedIn = request.session().get("accessToken").isPresent();
        String role = request.session().get("role").orElse("");
        return (signedIn && ROLES.contains(role)) ? role : "";
    }

    private static Map<String, String> sessionWith(Http.Request request, String access, String refresh) {
        Map<String, String> session = new HashMap<>(request.session().data());
        session.put("accessToken", access);
        session.put("refreshToken", refresh);
        return session;
    }
}