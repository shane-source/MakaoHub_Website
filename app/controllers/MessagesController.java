package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class MessagesController extends Controller {

    private static final Set<String> ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");

    /** The name of the paging parameter the backend expects. Change this one word if it differs. */
    private static final String CURSOR_PARAM = "cursor";

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
            .withZone(ZoneId.of("Africa/Nairobi"));

    /** One conversation in the inbox. An empty href means it can't be opened. */
    public record Chat(String title, String preview, long unread, String kind, String href,
                       String slug, String id, boolean archived, String date) {}

    public record MessagesView(List<Chat> chats, boolean loadFailed, boolean showArchived,
                               int archivedCount, String query) {}

    /** One message inside a conversation. */
    public record Msg(String id, String text, String time, boolean mine, boolean delivered, boolean masked,
                      long sentMillis) {}

    public record ThreadView(
            String title,
            String kind,
            String threadId,
            String channel,
            boolean open,
            String olderUrl,
            String slug,
            boolean archived,
            boolean hasOlder,
            String nextCursor,
            List<Msg> messages,
            boolean loadFailed) {}

    private final AuthedApi api;

    @Inject
    public MessagesController(AuthedApi api) {
        this.api = api;
    }

    // ---------- Inbox ----------

    public CompletionStage<Result> index(Http.Request request, String tab, String q) {
        String role = roleOf(request);
        if (role.isEmpty()) {
            return CompletableFuture.completedFuture(redirect("/get-started?mode=login"));
        }

        boolean showArchived = "archived".equals(tab);
        String query = q == null ? "" : q.trim();
        if (query.length() > 100) query = query.substring(0, 100);
        final String search = query;

        List<String> threadPaths = pathsFor(role);
        List<String> paths = new ArrayList<>(threadPaths);
        paths.add(statePath(role));

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) {
                return redirect("/get-started?mode=login").withNewSession();
            }

            Map<String, JsonNode> states = stateMap(batch.json.get(threadPaths.size()));
            List<Chat> chats = new ArrayList<>();
            boolean anyLoaded = false;
            int archivedCount = 0;

            for (int i = 0; i < threadPaths.size(); i++) {
                JsonNode json = batch.json.get(i);
                if (json == null) continue;
                anyLoaded = true;

                boolean roommate = threadPaths.get(i).contains("roommate-chat");
                String slug = roommate ? "roommate" : "home";
                String label = roommate ? "Roommate chat" : "Home enquiry";
                String stateKind = roommate ? "ROOMMATE" : "RENTAL";

                for (JsonNode n : listOf(json)) {
                    String id = idOf(n);
                    String placement = placement(states.get(stateKind + ":" + id), n.path("lastMessageAt").asText(""));
                    if (placement.equals("DELETED")) continue;
                    boolean archived = placement.equals("ARCHIVED");
                    if (archived) archivedCount++;
                    if (archived != showArchived) continue;

                    String title = title(n);
                    String preview = preview(n);
                    if (!search.isEmpty()
                            && !(title + " " + preview + " " + n.path("status").asText(""))
                                    .toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))) {
                        continue;
                    }

                    String href = isSafeId(id) ? "/dashboard/messages/" + slug + "/" + id : "";
                    chats.add(new Chat(title, preview, n.path("unreadCount").asLong(0), label, href,
                            slug, isSafeId(id) ? id : "", archived,
                            day(n.path("lastMessageAt").asText(""))));
                }
            }

            // Conversations with unread messages first; the rest keep the backend's order.
            chats.sort(Comparator.comparingLong(Chat::unread).reversed());

            return withFreshSession(request, batch,
                    ok(views.html.messages.render(role,
                            new MessagesView(chats, !anyLoaded, showArchived, archivedCount, search), request)));
        });
    }

    // ---------- Archive, restore and delete (this person's own view only) ----------

    public CompletionStage<Result> changeState(Http.Request request, String kind, String id) {
        String role = roleOf(request);
        if (role.isEmpty()) {
            return CompletableFuture.completedFuture(redirect("/get-started?mode=login"));
        }
        if (!isSafeId(id) || !allowed(role, kind)) {
            return CompletableFuture.completedFuture(redirect("/dashboard/messages"));
        }

        Map<String, String[]> form = request.body().asFormUrlEncoded();
        String state = form == null || form.get("state") == null || form.get("state").length == 0
                ? "" : form.get("state")[0];
        if (!Set.of("ARCHIVED", "ACTIVE", "DELETED").contains(state)) {
            return CompletableFuture.completedFuture(redirect("/dashboard/messages"));
        }

        String stateKind = kind.equals("roommate") ? "ROOMMATE" : "RENTAL";
        String path = statePath(role) + "/" + stateKind + "/" + id;
        ObjectNode body = Json.newObject();
        body.put("state", state);

        return api.put(request, path, body).thenApply(result -> {
            if (result.expired) {
                return redirect("/get-started?mode=login").withNewSession();
            }
            Result next = redirect("/dashboard/messages");
            if (result.refreshed()) {
                Map<String, String> session = new HashMap<>(request.session().data());
                session.put("accessToken", result.accessToken);
                session.put("refreshToken", result.refreshToken);
                next = next.withSession(session);
            }
            return next;
        });
    }

    // ---------- One conversation ----------

    public CompletionStage<Result> thread(Http.Request request, String kind, String id) {
        String role = roleOf(request);
        if (role.isEmpty()) {
            return CompletableFuture.completedFuture(redirect("/get-started?mode=login"));
        }
        // The id goes into a backend address, so only plain ids are accepted.
        if (!isSafeId(id) || !allowed(role, kind)) {
            return CompletableFuture.completedFuture(redirect("/dashboard/messages"));
        }

        boolean roommate = kind.equals("roommate");
        String base = threadsBase(role, roommate);

        List<String> paths = List.of("/api/v1/profile/me", base + "/" + id + "/messages", base, statePath(role));

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) {
                return redirect("/get-started?mode=login").withNewSession();
            }

            JsonNode me = batch.json.get(0);
            JsonNode messages = batch.json.get(1);
            JsonNode threads = batch.json.get(2);
            JsonNode thisState = stateMap(batch.json.get(3)).get((roommate ? "ROOMMATE" : "RENTAL") + ":" + id);
            String lastAt = "";
            if (threads != null) {
                for (JsonNode n : listOf(threads)) {
                    if (idOf(n).equals(id)) { lastAt = n.path("lastMessageAt").asText(""); break; }
                }
            }
            boolean archivedNow = placement(thisState, lastAt).equals("ARCHIVED");

            String myId = me == null ? "" : me.path("id").asText("");

            // The title and open/closed state come from the matching row in the conversation list.
            String title = "Conversation";
            boolean open = true;
            if (threads != null) {
                for (JsonNode n : listOf(threads)) {
                    if (idOf(n).equals(id)) {
                        title = title(n);
                        String status = n.path("status").asText("");
                        open = status.isEmpty() || status.equals("OPEN");
                        break;
                    }
                }
            }

            String nextCursor = messages == null ? "" : messages.path("nextCursor").asText("");
            boolean hasOlder = messages != null && messages.path("hasNext").asBoolean(false) && !nextCursor.isEmpty();

            ThreadView view = new ThreadView(
                    title,
                    roommate ? "Roommate chat" : "Home enquiry",
                    id,
                    roommate ? "ROOMMATE" : "RENTAL",
                    open,
                    "/dashboard/messages/" + (roommate ? "roommate" : "home") + "/" + id + "/older",
                    roommate ? "roommate" : "home",
                    archivedNow,
                    hasOlder,
                    nextCursor,
                    afterDelete(toMessages(messages, myId), thisState),
                    messages == null);

            return withFreshSession(request, batch, ok(views.html.thread.render(role, view, request)));
        });
    }

    // ---------- Older messages (the page calls this with fetch) ----------

    public CompletionStage<Result> older(Http.Request request, String kind, String id, String cursor) {
        String role = roleOf(request);
        if (role.isEmpty()) {
            return CompletableFuture.completedFuture(unauthorized(error("expired")));
        }
        if (!isSafeId(id) || !allowed(role, kind) || cursor.isEmpty() || cursor.length() > 300) {
            return CompletableFuture.completedFuture(badRequest(error("invalid")));
        }

        String base = threadsBase(role, kind.equals("roommate"));
        String path = base + "/" + id + "/messages?" + CURSOR_PARAM + "="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8);

        String stateKind = kind.equals("roommate") ? "ROOMMATE" : "RENTAL";
        return api.getAll(request, List.of("/api/v1/profile/me", path, statePath(role))).thenApply(batch -> {
            if (batch.expired) {
                return unauthorized(error("expired"));
            }
            JsonNode olderState = stateMap(batch.json.get(2)).get(stateKind + ":" + id);
            JsonNode me = batch.json.get(0);
            JsonNode page = batch.json.get(1);
            if (page == null) {
                return withFreshSession(request, batch, status(502, error("unavailable")));
            }

            String myId = me == null ? "" : me.path("id").asText("");
            String nextCursor = page.path("nextCursor").asText("");
            boolean hasNext = page.path("hasNext").asBoolean(false) && !nextCursor.isEmpty();

            ArrayNode list = Json.newArray();
            for (Msg m : afterDelete(toMessages(page, myId), olderState)) {
                ObjectNode node = Json.newObject();
                node.put("id", m.id());
                node.put("text", m.text());
                node.put("time", m.time());
                node.put("mine", m.mine());
                node.put("delivered", m.delivered());
                node.put("masked", m.masked());
                list.add(node);
            }

            ObjectNode reply = Json.newObject();
            reply.set("messages", list);
            reply.put("hasNext", hasNext);
            reply.put("nextCursor", hasNext ? nextCursor : "");
            return withFreshSession(request, batch, ok(reply));
        });
    }


    // ---------- Archive and delete helpers ----------

    private static String statePath(String role) {
        return "/api/v1/" + role.toLowerCase(Locale.ROOT) + "/chat-state";
    }

    /** The person's own archived or deleted conversations, keyed by "KIND:threadId". */
    private static Map<String, JsonNode> stateMap(JsonNode json) {
        Map<String, JsonNode> map = new HashMap<>();
        if (json == null) return map;
        for (JsonNode n : listOf(json)) {
            map.put(n.path("kind").asText("") + ":" + n.path("threadId").asText(""), n);
        }
        return map;
    }

    private static long millis(String iso) {
        try {
            return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (Exception e) {
            return -1;
        }
    }

    /** A message that arrives after archiving or deleting brings the conversation back to the inbox. */
    private static String placement(JsonNode state, String lastMessageAt) {
        if (state == null) return "ACTIVE";
        long last = millis(lastMessageAt);
        long changed = millis(state.path("changedAt").asText(""));
        if (last >= 0 && changed >= 0 && last > changed) return "ACTIVE";
        String value = state.path("state").asText("ACTIVE");
        return value.isEmpty() ? "ACTIVE" : value;
    }

    /** After a delete, anything sent before it stays hidden even if the conversation comes back. */
    private static List<Msg> afterDelete(List<Msg> items, JsonNode state) {
        if (state == null) return items;
        long cleared = millis(state.path("clearedAt").asText(""));
        if (cleared < 0) return items;
        List<Msg> kept = new ArrayList<>();
        for (Msg m : items) {
            if (m.sentMillis() > cleared) kept.add(m);
        }
        return kept;
    }

    // ---------- Helpers ----------

    private static String threadsBase(String role, boolean roommate) {
        return "/api/v1/" + role.toLowerCase(Locale.ROOT)
                + (roommate ? "/roommate-chat" : "/rental-chat") + "/threads";
    }

    /** Turns a page of backend messages into rows for the page, oldest first, like the app. */
    private static List<Msg> toMessages(JsonNode messages, String myId) {
        List<JsonNode> raw = new ArrayList<>();
        if (messages != null) raw.addAll(listOf(messages));
        raw.sort(Comparator.comparing((JsonNode n) -> n.path("createdAt").asText(""))
                .thenComparing(n -> n.path("id").asText("")));

        List<Msg> list = new ArrayList<>();
        for (JsonNode n : raw) {
            String text = firstText(n, "content", "body", "text", "message");
            String messageId = n.path("id").asText("");
            if (text.isEmpty() || !isSafeId(messageId)) continue;

            boolean mine = !myId.isEmpty() && n.path("senderId").asText("").equals(myId);
            JsonNode deliveredAt = n.path("deliveredAt");
            boolean delivered = !deliveredAt.isMissingNode() && !deliveredAt.isNull()
                    && !deliveredAt.asText("").isEmpty();

            list.add(new Msg(
                    messageId,
                    text,
                    clock(n.path("createdAt").asText("")),
                    mine,
                    delivered,
                    n.path("contactMasked").asBoolean(false),
                    instantMillis(n.path("createdAt").asText(""))));
        }
        return list;
    }

    private static JsonNode error(String code) {
        ObjectNode node = Json.newObject();
        node.put("error", code);
        return node;
    }

    private static String roleOf(Http.Request request) {
        boolean signedIn = request.session().get("accessToken").isPresent();
        String role = request.session().get("role").orElse("");
        return (signedIn && ROLES.contains(role)) ? role : "";
    }

    private static List<String> pathsFor(String role) {
        if (role.equals("LANDLORD")) {
            return List.of("/api/v1/landlord/rental-chat/threads");
        }
        if (role.equals("SUBLESSOR")) {
            return List.of("/api/v1/sublessor/roommate-chat/threads");
        }
        return List.of(
                "/api/v1/renter/rental-chat/threads",
                "/api/v1/renter/roommate-chat/threads");
    }

    /** Which kinds of chat each role may open. */
    private static boolean allowed(String role, String kind) {
        if (role.equals("LANDLORD")) return kind.equals("home");
        if (role.equals("SUBLESSOR")) return kind.equals("roommate");
        return kind.equals("home") || kind.equals("roommate");
    }

    private static boolean isSafeId(String id) {
        return id != null && id.matches("[A-Za-z0-9-]{1,64}");
    }

    /** The backend may return a plain list or an object with a "content" or "items" list. */
    private static List<JsonNode> listOf(JsonNode json) {
        List<JsonNode> out = new ArrayList<>();
        JsonNode list = json.isArray() ? json : (json.has("content") ? json.path("content") : json.path("items"));
        if (list.isArray()) list.forEach(out::add);
        return out;
    }

    private static String idOf(JsonNode n) {
        return firstText(n, "id", "conversationId", "threadId");
    }

    private static String title(JsonNode n) {
        String value = firstText(n, "counterpartyName", "landlordName", "renterName", "title");
        return value.isEmpty() ? "Conversation" : value;
    }

    private static String preview(JsonNode n) {
        JsonNode last = n.path("lastMessage");
        if (last.isTextual()) return last.asText();
        if (last.isObject()) {
            String value = firstText(last, "content", "body", "text", "message");
            if (!value.isEmpty()) return value;
        }
        return n.path("lastMessagePreview").asText("");
    }

    private static String firstText(JsonNode n, String... fields) {
        for (String field : fields) {
            String value = n.path(field).asText("");
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static long instantMillis(String iso) {
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            long value = millis(iso);
            return value < 0 ? Long.MAX_VALUE : value;
        }
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
            .withZone(ZoneId.of("Africa/Nairobi"));

    private static String day(String iso) {
        try {
            return DAY.format(Instant.parse(iso));
        } catch (Exception e) {
            return "";
        }
    }

    /** Kenyan time, like the app. */
    private static String clock(String iso) {
        try {
            return CLOCK.format(Instant.parse(iso));
        } catch (Exception e) {
            return "";
        }
    }

    /** Saves refreshed tokens to the session when the backend renewed them during the call. */
    private static Result withFreshSession(Http.Request request, AuthedApi.Batch batch, Result page) {
        if (!batch.refreshed()) return page;
        Map<String, String> session = new HashMap<>(request.session().data());
        session.put("accessToken", batch.accessToken);
        session.put("refreshToken", batch.refreshToken);
        return page.withSession(session);
    }
}