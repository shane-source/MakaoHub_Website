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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

/** Upload and submit verification documents. The backend runs the same checks as for the Android app. */
public class VerificationController extends Controller {

    private static final Logger LOG = LoggerFactory.getLogger(VerificationController.class);

    private static final String LOGIN = "/get-started?mode=login";
    private static final String PAGE = "/dashboard/verification";
    private static final long MAX_BYTES = 10_485_760L; // 10 MB, the backend's limit

    // Storage gets the file directly. It never follows redirects and carries no Makao credentials.
    private static final HttpClient STORAGE = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** One document a person has to provide. */
    public record Spec(String type, String label, String hint, boolean pdf, boolean selfie) {}

    /** One document, already worded for the page. */
    public record Doc(String type, String label, String hint, String state, boolean ok, boolean pdf,
                      boolean selfie, boolean replace) {}

    public record VerificationView(
            String overview,
            boolean verified,
            boolean pending,
            String reason,
            List<Doc> docs,
            boolean canSubmit,
            boolean loadFailed) {}

    private static final class Ctx {
        String access;
        String refresh;
        boolean renewed;

        Ctx(Http.Request request) {
            access = request.session().get("accessToken").orElse("");
            refresh = request.session().get("refreshToken").orElse("");
        }
    }

    private final OwnerApi api;

    @Inject
    public VerificationController(OwnerApi api) {
        this.api = api;
    }

    // ---------- The page ----------

    public CompletionStage<Result> page(Http.Request request) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);

        String role = role(request);
        Ctx c = new Ctx(request);
        String base = base(role);

        return call(c, "GET", base, null).thenCompose(status -> {
            if (status.expired()) return done(redirect(LOGIN).withNewSession());
            return call(c, "GET", base + "/documents", null).thenApply(documents -> {
                if (documents.expired()) return redirect(LOGIN).withNewSession();

                String state = status.ok() ? status.text("status") : "";
                boolean verified = state.equals("VERIFIED");
                boolean pending = state.equals("PENDING");

                List<JsonNode> list = new ArrayList<>();
                JsonNode json = documents.json();
                JsonNode items = json == null ? null : (json.isArray() ? json : json.path("items"));
                if (items != null && items.isArray()) items.forEach(list::add);

                List<Doc> docs = new ArrayList<>();
                boolean allReady = true;
                for (Spec spec : specs(role)) {
                    JsonNode match = find(list, spec.type());
                    boolean ready = match != null && match.path("status").asText("").equals("READY")
                            && match.path("securityChecked").asBoolean(false);
                    if (!ready) allReady = false;
                    docs.add(new Doc(spec.type(), spec.label(), spec.hint(), describe(match), ready,
                            spec.pdf(), spec.selfie(), match != null));
                }

                VerificationView view = new VerificationView(
                        overview(state),
                        verified,
                        pending,
                        status.ok() && status.json().hasNonNull("rejectionReason")
                                ? status.json().path("rejectionReason").asText("") : "",
                        docs,
                        allReady && !verified && !pending && status.ok() && documents.ok(),
                        !status.ok() && !documents.ok());

                return finish(c, request, ok(views.html.verification.render(role, view, request)));
            });
        });
    }

    // ---------- Upload one document ----------

    public CompletionStage<Result> upload(Http.Request request, String type) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);

        String role = role(request);
        Spec spec = null;
        for (Spec s : specs(role)) if (s.type().equals(type)) spec = s;
        if (spec == null) return done(redirect(PAGE));

        Http.MultipartFormData<TemporaryFile> form = request.body().asMultipartFormData();
        Http.MultipartFormData.FilePart<TemporaryFile> part = null;
        if (form != null) {
            for (Http.MultipartFormData.FilePart<TemporaryFile> p : form.getFiles()) {
                if (p.getFilename() != null && !p.getFilename().isEmpty()) { part = p; break; }
            }
        }
        if (part == null) {
            return done(redirect(PAGE).flashing("vf", "none"));
        }

        Path file = part.getRef().path();
        String mime;
        long size;
        try {
            size = Files.size(file);
            mime = sniff(file, spec.pdf());
        } catch (IOException e) {
            return done(redirect(PAGE).flashing("vf", "unreadable"));
        }
        if (mime == null) {
            return done(redirect(PAGE).flashing("vf", spec.pdf() ? "need-pdf" : "need-image"));
        }
        if (size < 1 || size > MAX_BYTES) {
            return done(redirect(PAGE).flashing("vf", "too-big"));
        }

        Ctx c = new Ctx(request);
        String base = base(role);
        String chosen = mime;

        // Replacing is allowed when a copy of this document already exists.
        return call(c, "GET", base + "/documents", null).thenCompose(existing -> {
            if (existing.expired()) return done(redirect(LOGIN).withNewSession());

            boolean replace = false;
            JsonNode json = existing.json();
            JsonNode items = json == null ? null : (json.isArray() ? json : json.path("items"));
            if (items != null && items.isArray()) {
                for (JsonNode d : items) {
                    if (d.path("documentType").asText("").equals(type) && !d.path("status").asText("").equals("DELETED")) {
                        replace = true;
                    }
                }
            }

            ObjectNode ask = Json.newObject();
            ask.put("documentType", type);
            ask.put("contentType", chosen);
            ask.put("replaceExisting", replace);

            return call(c, "POST", base + "/documents/upload-url", ask).thenCompose(ticket -> {
                if (ticket.expired()) return done(redirect(LOGIN).withNewSession());
                if (!ticket.ok()) {
                    LOG.warn("verification ticket failed: status={} body={}", ticket.status(), ticket.json());
                    return done(finish(c, request, redirect(PAGE).flashing(java.util.Map.of("vf", "refused", "vfMsg", ticket.message()))));
                }
                String documentId = ticket.text("documentId");
                String url = ticket.text("uploadUrl");
                if (!isSafeId(documentId) || !signedStorage(url)) {
                    LOG.warn("verification ticket had an unusable address");
                    return done(finish(c, request, redirect(PAGE).flashing("vf", "failed")));
                }
                String sendAs = ticket.text("contentType").isEmpty() ? chosen : ticket.text("contentType");

                return store(url, sendAs, file).thenCompose(stored -> {
                    if (!stored) return done(finish(c, request, redirect(PAGE).flashing("vf", "failed")));
                    return call(c, "POST", base + "/documents/" + documentId + "/confirm", null).thenApply(confirmed -> {
                        if (confirmed.expired()) return redirect(LOGIN).withNewSession();
                        if (!confirmed.ok()) {
                            LOG.warn("verification confirm failed: status={} body={}", confirmed.status(), confirmed.json());
                            return finish(c, request, redirect(PAGE).flashing(java.util.Map.of("vf", "refused", "vfMsg", confirmed.message())));
                        }
                        return finish(c, request, redirect(PAGE).flashing("vf", "saved"));
                    });
                });
            });
        });
    }

    // ---------- Send everything for review ----------

    public CompletionStage<Result> submit(Http.Request request) {
        Result blocked = gate(request);
        if (blocked != null) return done(blocked);

        Ctx c = new Ctx(request);
        return call(c, "POST", base(role(request)) + "/submit", null).thenApply(reply -> {
            if (reply.expired()) return redirect(LOGIN).withNewSession();
            if (!reply.ok()) {
                LOG.warn("verification submit failed: status={} body={}", reply.status(), reply.json());
                return finish(c, request, redirect(PAGE).flashing(java.util.Map.of("vf", "refused", "vfMsg", reply.message())));
            }
            return finish(c, request, redirect(PAGE).flashing("vf", "submitted"));
        });
    }

    // ---------- Wording and rules ----------

    private static List<Spec> specs(String role) {
        List<Spec> list = new ArrayList<>();
        list.add(new Spec("ID_FRONT", "National ID, front", "A clear JPEG or PNG photo of the front.", false, false));
        list.add(new Spec("ID_BACK", "National ID, back", "A clear JPEG or PNG photo of the back.", false, false));
        list.add(new Spec("SELFIE", "Live photo", "A clear photo of your face, looking at the camera.", false, true));
        if (role.equals("LANDLORD")) {
            list.add(new Spec("OWNERSHIP_PROOF", "Proof of ownership", "A PDF, such as a title deed or lease.", true, false));
        }
        if (role.equals("SUBLESSOR")) {
            list.add(new Spec("RENTAL_AGREEMENT", "Rental agreement", "A PDF of your rental agreement.", true, false));
        }
        return list;
    }

    private static JsonNode find(List<JsonNode> docs, String type) {
        for (JsonNode d : docs) {
            if (d.path("documentType").asText("").equals(type) && !d.path("status").asText("").equals("DELETED")) return d;
        }
        return null;
    }

    private static String describe(JsonNode doc) {
        if (doc == null) return "Not uploaded";
        String status = doc.path("status").asText("");
        if (status.equals("REJECTED")) return "Rejected. Upload a new one.";
        if (status.equals("UPLOADING")) return "Processing";
        if (status.equals("READY")) {
            return doc.path("securityChecked").asBoolean(false) ? "Uploaded" : "Being checked";
        }
        return "Status unavailable";
    }

    private static String overview(String status) {
        switch (status) {
            case "VERIFIED": return "Verified";
            case "PENDING": return "Pending review";
            case "REJECTED": return "Verification rejected";
            case "": return "Not started";
            default: return "Not started";
        }
    }

    // ---------- Storage ----------

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
    private static String sniff(Path path, boolean pdf) throws IOException {
        byte[] b = new byte[8];
        int n;
        try (InputStream in = Files.newInputStream(path)) {
            n = in.readNBytes(b, 0, b.length);
        }
        if (pdf) {
            return (n > 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') ? "application/pdf" : null;
        }
        if (n > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (n > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        return null;
    }

    // ---------- Helpers ----------

    private CompletionStage<OwnerApi.Reply> call(Ctx c, String method, String path, JsonNode body) {
        return api.call(c.access, c.refresh, method, path, body).thenApply(reply -> {
            if (reply.access() != null) {
                c.access = reply.access();
                c.refresh = reply.refresh();
                c.renewed = true;
            }
            return reply;
        });
    }

    private static Result finish(Ctx c, Http.Request request, Result result) {
        if (!c.renewed) return result;
        Map<String, String> session = new HashMap<>(request.session().data());
        session.put("accessToken", c.access);
        session.put("refreshToken", c.refresh);
        return result.withSession(session);
    }

    private static Result gate(Http.Request request) {
        if (request.session().get("accessToken").isEmpty()) return redirect(LOGIN);
        String role = role(request);
        if (!role.equals("RENTER") && !role.equals("LANDLORD") && !role.equals("SUBLESSOR")) return redirect("/dashboard");
        return null;
    }

    private static String role(Http.Request request) {
        return request.session().get("role").orElse("");
    }

    private static String base(String role) {
        return "/api/v1/" + role.toLowerCase(Locale.ROOT) + "/verification";
    }

    private static boolean isSafeId(String id) {
        return id != null && id.matches("[A-Za-z0-9-]{1,64}");
    }

    private static <T> CompletionStage<T> done(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
