package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Files.TemporaryFile;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.OwnerApi;

public class ListingMediaController extends Controller {

    private static final Logger LOG = LoggerFactory.getLogger(ListingMediaController.class);

    private static final Set<String> OWNERS = Set.of("LANDLORD", "SUBLESSOR");
    private static final String LOGIN = "/get-started?mode=login";
    private static final long MAX_BYTES = 52_428_800L; // 50 MB, the same limit as the Android app

    // Storage gets the file directly. It never follows redirects and carries no Makao credentials.
    private static final HttpClient STORAGE = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** One uploaded file, already worded for the page. */
    public record MediaRow(String id, boolean video, String size, boolean confirmed, String photo) {}

    public record MediaView(
            String id,
            String title,
            String status,
            boolean editable,
            long images,
            long videos,
            List<MediaRow> rows,
            boolean loadFailed) {}

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

    /** What has happened so far while several files are uploaded one after another. */
    private static final class Progress {
        final Set<Integer> used = new HashSet<>();
        boolean hasVideo;
        int saved;
        String error;
        boolean expired;
        boolean stop;

        void fail(String message) {
            if (error == null) error = message;
            stop = true;
        }

        void expire() {
            expired = true;
            stop = true;
        }
    }

    private final OwnerApi api;

    @Inject
    public ListingMediaController(OwnerApi api) {
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

            return call(c, "GET", one + "/media", null).thenCompose(media -> {
                if (media.expired()) return done(redirect(LOGIN).withNewSession());

                return call(c, "GET", one + "/photos", null).thenApply(photos -> {
                    if (photos.expired()) return redirect(LOGIN).withNewSession();

                    // Signed read addresses, keyed by media id. Only secure addresses are used.
                    Map<String, String> urls = new HashMap<>();
                    for (JsonNode n : items(photos.json())) {
                        String url = n.path("signedReadUrl").asText("");
                        if (url.startsWith("https://")) urls.put(n.path("id").asText(""), url);
                    }

                    List<JsonNode> raw = new ArrayList<>(items(media.json()));
                    raw.sort(Comparator.comparingInt((JsonNode n) -> n.path("sortOrder").asInt(0)));

                    List<MediaRow> rows = new ArrayList<>();
                    long images = 0;
                    long videos = 0;
                    for (JsonNode n : raw) {
                        String mediaId = n.path("id").asText("");
                        if (!isSafeId(mediaId)) continue;
                        boolean video = n.path("mediaType").asText("").equals("VIDEO");
                        if (video) videos++; else images++;
                        rows.add(new MediaRow(
                                mediaId,
                                video,
                                size(n.path("sizeBytes").asLong(0)),
                                n.path("confirmed").asBoolean(false),
                                video ? "" : urls.getOrDefault(mediaId, "")));
                    }

                    String status = found.path("status").asText("");
                    MediaView view = new MediaView(
                            id,
                            found.path("title").asText("Your listing"),
                            status,
                            status.equals("DRAFT"),
                            images,
                            videos,
                            rows,
                            !media.ok());
                    return finish(c, request, ok(views.html.listingMedia.render(role, view, request)));
                });
            });
        });
    }

    // ---------- Upload ----------

    public CompletionStage<Result> upload(Http.Request request, String id) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);
        if (!isSafeId(id)) return done(redirect("/dashboard/listings"));

        String role = role(request);
        String page = "/dashboard/listings/" + id + "/photos";

        Http.MultipartFormData<TemporaryFile> form = request.body().asMultipartFormData();
        List<Http.MultipartFormData.FilePart<TemporaryFile>> parts = new ArrayList<>();
        if (form != null) {
            for (Http.MultipartFormData.FilePart<TemporaryFile> part : form.getFiles()) {
                if (part.getFilename() != null && !part.getFilename().isEmpty()) parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            return done(redirect(page).flashing("mediaErr", "Choose at least one photo or video."));
        }
        if (parts.size() > 12) {
            return done(redirect(page).flashing("mediaErr", "Choose up to 12 files at a time."));
        }

        Ctx c = new Ctx(request);
        String base = owner(role) + "/listings/" + id;

        // The existing files decide which positions are free and whether the video slot is taken.
        return call(c, "GET", base + "/media", null).thenCompose(existing -> {
            if (existing.expired()) return done(redirect(LOGIN).withNewSession());
            if (!existing.ok()) {
                return done(finish(c, request, redirect(page).flashing("mediaErr", existing.message())));
            }

            Progress progress = new Progress();
            for (JsonNode n : items(existing.json())) {
                progress.used.add(n.path("sortOrder").asInt(-1));
                if (n.path("mediaType").asText("").equals("VIDEO")) progress.hasVideo = true;
            }

            return next(c, base, parts, 0, progress).thenApply(ignored -> {
                if (progress.expired) return redirect(LOGIN).withNewSession();

                Map<String, String> flash = new HashMap<>();
                if (progress.saved > 0) {
                    flash.put("mediaNote", progress.saved == 1 ? "1 file uploaded." : progress.saved + " files uploaded.");
                }
                if (progress.error != null) flash.put("mediaErr", progress.error);
                return finish(c, request, redirect(page).flashing(flash));
            });
        });
    }

    /** Files go one after another, so the tokens are never used twice at once. */
    private CompletionStage<Void> next(Ctx c, String base,
                                       List<Http.MultipartFormData.FilePart<TemporaryFile>> parts,
                                       int index, Progress progress) {
        if (index >= parts.size()) return done(null);
        return one(c, base, parts.get(index).getRef().path(), progress).thenCompose(ignored ->
                progress.stop ? done(null) : next(c, base, parts, index + 1, progress));
    }

    /** The three steps for one file: ask for an address, send it there, confirm it. */
    private CompletionStage<Void> one(Ctx c, String base, Path path, Progress p) {
        long size;
        String mime;
        try {
            size = Files.size(path);
            mime = sniff(path);
        } catch (IOException e) {
            p.fail("One of the files couldn't be read.");
            return done(null);
        }
        if (mime == null) {
            p.fail("Choose JPEG, PNG or WebP photos, or an MP4 video.");
            return done(null);
        }
        if (size < 1 || size > MAX_BYTES) {
            p.fail("Each file must be 50 MB or smaller.");
            return done(null);
        }
        boolean video = mime.equals("video/mp4");
        if (video && p.hasVideo) {
            p.fail("A listing can have one video.");
            return done(null);
        }
        int order = freeOrder(p.used);
        if (order < 0) {
            p.fail("This listing already has the most files it can hold.");
            return done(null);
        }

        ObjectNode ask = Json.newObject();
        ask.put("mediaType", video ? "VIDEO" : "IMAGE");
        ask.put("contentType", mime);
        ask.put("sizeBytes", size);
        ask.put("sortOrder", order);

        return call(c, "POST", base + "/media/upload-url", ask).thenCompose(ticket -> {
            if (ticket.expired()) {
                p.expire();
                return done(null);
            }
            String mediaId = ticket.text("mediaId");
            String url = ticket.text("uploadUrl");
            if (!ticket.ok()) {
                LOG.warn("media ticket failed: status={} body={}", ticket.status(), ticket.json());
                p.fail(ticket.message());
                return done(null);
            }
            if (!isSafeId(mediaId) || !signedStorage(url)) {
                LOG.warn("media ticket had an unusable address");
                p.fail("The upload address wasn't accepted. Please try again.");
                return done(null);
            }
            String type = ticket.text("contentType").isEmpty() ? mime : ticket.text("contentType");

            return store(url, type, path).thenCompose(stored -> {
                if (!stored) {
                    p.fail("A file couldn't be uploaded. Check your connection and try again.");
                    // Remove the empty placeholder so it doesn't pile up. A failure here is harmless.
                    return call(c, "DELETE", base + "/media/" + mediaId, null).thenApply(ListingMediaController::nothing);
                }
                p.used.add(order);
                return call(c, "POST", base + "/media/" + mediaId + "/confirm", null).thenApply(done -> {
                    if (done.expired()) {
                        p.expire();
                    } else if (!done.ok()) {
                        LOG.warn("media confirm failed: status={} body={}", done.status(), done.json());
                        p.fail("The file was uploaded, but the server hasn't confirmed it yet. Use “Confirm stored upload”.");
                    } else {
                        p.saved++;
                        if (video) p.hasVideo = true;
                    }
                    return null;
                });
            });
        });
    }

    // ---------- Replace one photo ----------

    /** As in the app: the new photo is uploaded and confirmed first, then the old one is removed. */
    public CompletionStage<Result> replace(Http.Request request, String id, String mid) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);
        if (!isSafeId(id) || !isSafeId(mid)) return done(redirect("/dashboard/listings"));

        String role = role(request);
        String page = "/dashboard/listings/" + id + "/photos";

        Http.MultipartFormData<TemporaryFile> form = request.body().asMultipartFormData();
        Http.MultipartFormData.FilePart<TemporaryFile> part = null;
        if (form != null) {
            for (Http.MultipartFormData.FilePart<TemporaryFile> f : form.getFiles()) {
                if (f.getFilename() != null && !f.getFilename().isEmpty()) { part = f; break; }
            }
        }
        if (part == null) {
            return done(redirect(page).flashing("mediaErr", "Choose a JPEG, PNG or WebP photo as the replacement."));
        }
        final Path file = part.getRef().path();

        Ctx c = new Ctx(request);
        String base = owner(role) + "/listings/" + id;

        return call(c, "GET", base + "/media", null).thenCompose(existing -> {
            if (existing.expired()) return done(redirect(LOGIN).withNewSession());
            if (!existing.ok()) {
                return done(finish(c, request, redirect(page).flashing("mediaErr", existing.message())));
            }

            Progress progress = new Progress();
            boolean oldIsPhoto = false;
            for (JsonNode n : items(existing.json())) {
                progress.used.add(n.path("sortOrder").asInt(-1));
                if (n.path("mediaType").asText("").equals("VIDEO")) progress.hasVideo = true;
                if (mid.equals(n.path("id").asText("")) && n.path("mediaType").asText("").equals("IMAGE")) oldIsPhoto = true;
            }
            if (!oldIsPhoto) {
                return done(finish(c, request, redirect(page).flashing("mediaErr", "Only photos can be replaced here.")));
            }

            return one(c, base, file, progress).thenCompose(ignored -> {
                if (progress.expired) return done(redirect(LOGIN).withNewSession());
                if (progress.error != null || progress.saved < 1) {
                    String message = progress.error != null ? progress.error : "The replacement wasn't saved.";
                    return done(finish(c, request, redirect(page).flashing("mediaErr", message)));
                }
                return call(c, "DELETE", base + "/media/" + mid, null).thenApply(removed -> {
                    if (removed.expired()) return redirect(LOGIN).withNewSession();
                    Result back = removed.ok()
                            ? redirect(page).flashing("mediaNote", "Photo replaced.")
                            : redirect(page).flashing("mediaErr", "The new photo was added, but the old one couldn't be removed. Remove it below.");
                    return finish(c, request, back);
                });
            });
        });
    }

    // ---------- Confirm a stored upload, or remove a file ----------

    public CompletionStage<Result> confirm(Http.Request request, String id, String mid) {
        return single(request, id, mid, "POST", "/confirm", "File confirmed.");
    }

    public CompletionStage<Result> remove(Http.Request request, String id, String mid) {
        return single(request, id, mid, "DELETE", "", "File removed.");
    }

    private CompletionStage<Result> single(Http.Request request, String id, String mid,
                                           String method, String suffix, String success) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);
        if (!isSafeId(id) || !isSafeId(mid)) return done(redirect("/dashboard/listings"));

        String role = role(request);
        String page = "/dashboard/listings/" + id + "/photos";
        Ctx c = new Ctx(request);

        return call(c, method, owner(role) + "/listings/" + id + "/media/" + mid + suffix, null).thenApply(reply -> {
            if (reply.expired()) return redirect(LOGIN).withNewSession();
            if (!reply.ok()) LOG.warn("media {} failed: status={} body={}", method, reply.status(), reply.json());
            Result back = reply.ok()
                    ? redirect(page).flashing("mediaNote", success)
                    : redirect(page).flashing("mediaErr", reply.message());
            return finish(c, request, back);
        });
    }

    // ---------- Sending the file to storage ----------

    /** Only the signed Supabase address the backend gave us is accepted, as in the Android app. */
    private static boolean signedStorage(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            return "https".equals(uri.getScheme())
                    && host != null && host.endsWith(".supabase.co")
                    && uri.getUserInfo() == null
                    && uri.getRawPath() != null
                    && uri.getRawPath().startsWith("/storage/v1/object/upload/sign/");
        } catch (Exception e) {
            return false;
        }
    }

    /** Sends the file straight from disk, so a 50 MB video never sits in memory. */
    private static CompletionStage<Boolean> store(String url, String contentType, Path file) {
        try {
            HttpRequest put = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", contentType)
                    .PUT(HttpRequest.BodyPublishers.ofFile(file))
                    .build();
            return STORAGE.sendAsync(put, HttpResponse.BodyHandlers.discarding())
                    .thenApply(response -> response.statusCode() >= 200 && response.statusCode() < 300)
                    .exceptionally(error -> false);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    /** Decides the type from the file's first bytes, not from the name the browser sent. */
    private static String sniff(Path path) throws IOException {
        byte[] b = new byte[16];
        int n;
        try (InputStream in = Files.newInputStream(path)) {
            n = in.readNBytes(b, 0, b.length);
        }
        if (n > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (n > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (n > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        if (n > 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
            return "video/mp4";
        }
        return null;
    }

    /** The first position from 0 to 30 that isn't taken, like the app. */
    private static int freeOrder(Set<Integer> used) {
        for (int i = 0; i <= 30; i++) {
            if (!used.contains(i)) return i;
        }
        return -1;
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

    private static String size(long bytes) {
        if (bytes >= 1_048_576L) return String.format(Locale.UK, "%.1f MB", bytes / 1_048_576.0);
        return Math.max(1, bytes / 1024) + " KB";
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

    private static Void nothing(Object ignored) {
        return null;
    }

    private static <T> CompletionStage<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }
}