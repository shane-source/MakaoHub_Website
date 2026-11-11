package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class PhotoController extends Controller {

    private static final Set<String> ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");
    private static final long MAX_BYTES = 5_242_880L; // the same 5 MB limit as the Android app

    // Storage gets the image directly. It never follows redirects and carries no Makao credentials.
    private static final HttpClient STORAGE = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AuthedApi api;

    @Inject
    public PhotoController(AuthedApi api) {
        this.api = api;
    }

    /**
     * Same three steps as the Android app:
     * 1) ask the backend for an upload address, 2) PUT the image there, 3) confirm it.
     */
    public CompletionStage<Result> change(Http.Request request) {
        boolean signedIn = request.session().get("accessToken").isPresent();
        String role = request.session().get("role").orElse("");
        if (!signedIn || !ROLES.contains(role)) {
            return completed(redirect("/get-started?mode=login"));
        }

        byte[] bytes = readImage(request);
        String type = bytes == null ? null : sniff(bytes);
        if (type == null) {
            return completed(finish(request, null, null, false));
        }

        String access = request.session().get("accessToken").orElse("");
        String refresh = request.session().get("refreshToken").orElse("");

        ObjectNode ask = Json.newObject();
        ask.put("contentType", type);

        return api.post(access, refresh, "/api/v1/profile/me/avatar/upload-url", ask).thenCompose(ticket -> {
            if (ticket.expired) {
                return completed(redirect("/get-started?mode=login").withNewSession());
            }
            String a1 = ticket.refreshed() ? ticket.accessToken : access;
            String r1 = ticket.refreshed() ? ticket.refreshToken : refresh;

            String uploadUrl = text(ticket.json, "uploadUrl");
            String objectPath = text(ticket.json, "objectPath");
            if (!ticket.ok() || !uploadUrl.startsWith("https://") || objectPath.isEmpty()) {
                return completed(finish(request, ticket.refreshed() ? a1 : null, r1, false));
            }
            String contentType = text(ticket.json, "contentType");
            if (contentType.isEmpty()) contentType = type;

            return store(uploadUrl, contentType, bytes).thenCompose(stored -> {
                if (!stored) {
                    return completed(finish(request, ticket.refreshed() ? a1 : null, r1, false));
                }
                ObjectNode confirm = Json.newObject();
                confirm.put("objectPath", objectPath);

                return api.post(a1, r1, "/api/v1/profile/me/avatar/confirm", confirm).thenApply(last -> {
                    if (last.expired) {
                        return redirect("/get-started?mode=login").withNewSession();
                    }
                    boolean renewed = ticket.refreshed() || last.refreshed();
                    String a2 = last.refreshed() ? last.accessToken : a1;
                    String r2 = last.refreshed() ? last.refreshToken : r1;
                    return finish(request, renewed ? a2 : null, r2, last.ok());
                });
            });
        });
    }

    // ---------- Steps ----------

    /** Reads the chosen file from the form. Returns null if it is missing, empty or too large. */
    private static byte[] readImage(Http.Request request) {
        Http.MultipartFormData<play.libs.Files.TemporaryFile> form = request.body().asMultipartFormData();
        if (form == null) return null;
        Http.MultipartFormData.FilePart<play.libs.Files.TemporaryFile> part = form.getFile("photo");
        if (part == null) return null;
        try {
            Path path = part.getRef().path();
            long size = Files.size(path);
            if (size == 0 || size > MAX_BYTES) return null;
            return Files.readAllBytes(path);
        } catch (IOException e) {
            return null;
        }
    }

    /** Decides the image type from the file's first bytes, not from the name the browser sent. */
    private static String sniff(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    /** PUTs the image to the signed address the backend gave us. */
    private static CompletionStage<Boolean> store(String url, String contentType, byte[] bytes) {
        try {
            HttpRequest put = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", contentType)
                    .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                    .build();
            return STORAGE.sendAsync(put, HttpResponse.BodyHandlers.discarding())
                    .thenApply(response -> response.statusCode() >= 200 && response.statusCode() < 300)
                    .exceptionally(error -> false);
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(false);
        }
    }

    // ---------- Helpers ----------

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    private static CompletionStage<Result> completed(Result result) {
        return CompletableFuture.completedFuture(result);
    }

    /** Back to the profile with a short message, saving renewed tokens if there are any. */
    private static Result finish(Http.Request request, String newAccess, String newRefresh, boolean success) {
        Result back = redirect("/dashboard/account").flashing("photo", success ? "ok" : "failed");
        if (newAccess != null) {
            Map<String, String> session = new HashMap<>(request.session().data());
            session.put("accessToken", newAccess);
            session.put("refreshToken", newRefresh);
            return back.withSession(session);
        }
        return back;
    }
}