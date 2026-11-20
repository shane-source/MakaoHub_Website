package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

public class ProfileController extends Controller {

    private static final Set<String> ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");

    /** One row in the "Verification documents" list. */
    public record DocRow(String label, String state, boolean ok) {}

    /** One row in "Your account". An empty href means the page is not built yet. */
    public record ActionRow(String title, String href, String icon) {}

    /** Everything the template needs, already cleaned up. */
    public record ProfileView(
            String fullName,
            String accountLabel,
            String avatarUrl,
            boolean verified,
            String verificationLine,
            String overview,
            List<DocRow> documents,
            List<ActionRow> actions,
            boolean loadFailed) {

        public String initial() {
            return fullName.isEmpty() ? "?" : fullName.substring(0, 1).toUpperCase(Locale.ROOT);
        }
    }

    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp");

    // Storage gets the request directly: no redirects, and no Makao credentials are sent to it.
    private static final HttpClient STORAGE = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final AuthedApi api;

    @Inject
    public ProfileController(AuthedApi api) {
        this.api = api;
    }

    public CompletionStage<Result> index(Http.Request request) {
        boolean signedIn = request.session().get("accessToken").isPresent();
        String role = request.session().get("role").orElse("");
        if (!signedIn || !ROLES.contains(role)) {
            return CompletableFuture.completedFuture(redirect("/get-started?mode=login"));
        }
        String sessionName = request.session().get("fullName").orElse("");
        String base = "/api/v1/" + role.toLowerCase(Locale.ROOT);

        List<String> paths = List.of(
                "/api/v1/profile/me",
                base + "/verification",
                base + "/verification/documents");

        return api.getAll(request, paths).thenApply(batch -> {
            if (batch.expired) {
                // The session is no longer valid: start again from sign in.
                return redirect("/get-started?mode=login").withNewSession();
            }

            JsonNode me = batch.json.get(0);
            JsonNode verification = batch.json.get(1);
            JsonNode documents = batch.json.get(2);

            String name = text(me, "fullName");
            if (name.isEmpty()) name = sessionName;

            String status = verification == null ? "" : verification.path("status").asText("");
            boolean verified = status.equals("VERIFIED");

            // The stored photo address is a temporary signed link that expires after a few minutes, so the page
            // never uses it directly. It points at /dashboard/profile/avatar, which fetches a fresh link each time.
            String avatar = text(me, "avatarUrl");
            if (!trustedAvatar(avatar)) {
                avatar = "";
            } else {
                boolean justChanged = "ok".equals(request.flash().get("photo").orElse(""));
                avatar = "/dashboard/profile/avatar?v=" + (justChanged ? System.currentTimeMillis() : pathVersion(avatar));
            }

            ProfileView view = new ProfileView(
                    name,
                    pretty(role) + " account",
                    avatar,
                    verified,
                    verified ? "Verified" : "Verification: " + (status.isEmpty() ? "not started" : words(status)),
                    overview(status),
                    documentRows(role, documents),
                    actionsFor(role),
                    me == null && verification == null);

            Result page = ok(views.html.profile.render(role, view, request));
            if (batch.refreshed()) {
                Map<String, String> session = new HashMap<>(request.session().data());
                session.put("accessToken", batch.accessToken);
                session.put("refreshToken", batch.refreshToken);
                return page.withSession(session);
            }
            return page;
        });
    }

    /** Serves the signed-in person's own profile photo from a stable address that never expires. */
    public CompletionStage<Result> avatar(Http.Request request) {
        boolean signedIn = request.session().get("accessToken").isPresent();
        if (!signedIn) {
            return CompletableFuture.completedFuture(status(401));
        }
        return api.getAll(request, List.of("/api/v1/profile/me")).thenCompose(batch -> {
            if (batch.expired) {
                return CompletableFuture.<Result>completedFuture(status(401));
            }
            String url = text(batch.json.get(0), "avatarUrl");
            if (!trustedAvatar(url)) {
                return CompletableFuture.<Result>completedFuture(notFound());
            }
            HttpRequest get;
            try {
                get = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
            } catch (IllegalArgumentException e) {
                return CompletableFuture.<Result>completedFuture(notFound());
            }
            return STORAGE.sendAsync(get, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
                String type = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                type = type.split(";")[0].trim();
                byte[] body = response.body();
                if (response.statusCode() != 200 || body == null || body.length == 0
                        || body.length > 10_485_760 || !IMAGE_TYPES.contains(type)) {
                    return status(502);
                }
                Result image = ok(body).as(type)
                        .withHeader("Cache-Control", "private, max-age=300")
                        .withHeader("X-Content-Type-Options", "nosniff");
                if (batch.refreshed()) {
                    Map<String, String> session = new HashMap<>(request.session().data());
                    session.put("accessToken", batch.accessToken);
                    session.put("refreshToken", batch.refreshToken);
                    return image.withSession(session);
                }
                return image;
            }).exceptionally(error -> status(502));
        });
    }

    /** Only a signed link on the project's own storage host is ever fetched. */
    private static boolean trustedAvatar(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            String path = uri.getRawPath();
            return host != null && host.endsWith(".supabase.co")
                    && path != null && path.contains("/storage/v1/object/sign/");
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Changes only when the stored file changes, not when the signed link is renewed. */
    private static String pathVersion(String url) {
        try {
            return Integer.toHexString(URI.create(url).getRawPath().hashCode());
        } catch (RuntimeException e) {
            return "0";
        }
    }

    // ---------- Rows in "Your account" ----------

    private static List<ActionRow> actionsFor(String role) {
        List<ActionRow> rows = new ArrayList<>();
        if (role.equals("RENTER")) {
            rows.add(new ActionRow("Saved homes", "/dashboard/saved", "heart"));
            rows.add(new ActionRow("Applications", "/dashboard/applications", "document"));
            rows.add(new ActionRow("Bookings", "/dashboard/my-bookings", "payments"));
            rows.add(new ActionRow("Payment history", "/dashboard/payments", "payments"));
        } else {
            rows.add(new ActionRow("My listings", "/dashboard/listings", "home"));
            rows.add(new ActionRow("Boost & plans", "/dashboard/plans", "bolt"));
            rows.add(new ActionRow("Bookings & payouts", "/dashboard/bookings", "payments"));
            rows.add(new ActionRow("Payment history", "/dashboard/payments", "payments"));
        }
        return rows;
    }

    // ---------- Verification documents (same rules as the Android app) ----------

    private static List<DocRow> documentRows(String role, JsonNode json) {
        List<JsonNode> docs = new ArrayList<>();
        JsonNode list = json == null ? null : (json.isArray() ? json : json.path("items"));
        if (list != null && list.isArray()) list.forEach(docs::add);

        List<DocRow> rows = new ArrayList<>();
        rows.add(row("National ID (front and back)", docs, "ID_FRONT", "ID_BACK"));
        rows.add(row("Face match selfie", docs, "SELFIE"));
        if (role.equals("LANDLORD")) rows.add(row("Ownership proof", docs, "OWNERSHIP_PROOF"));
        if (role.equals("SUBLESSOR")) rows.add(row("Rental agreement", docs, "RENTAL_AGREEMENT"));
        return rows;
    }

    private static DocRow row(String label, List<JsonNode> docs, String... types) {
        String state = state(docs, types);
        return new DocRow(label, state, state.equals("Uploaded"));
    }

    /** Document upload state, which is separate from the verification decision. */
    private static String state(List<JsonNode> docs, String... types) {
        boolean anyRejected = false;
        boolean anyUploading = false;
        boolean anyOther = false;
        boolean allReady = true;
        boolean allMissing = true;

        for (String type : types) {
            JsonNode match = null;
            for (JsonNode doc : docs) {
                boolean sameType = doc.path("documentType").asText("").equals(type);
                boolean deleted = doc.path("status").asText("").equals("DELETED");
                if (sameType && !deleted) { match = doc; break; }
            }
            if (match == null) { allReady = false; continue; }

            allMissing = false;
            String status = match.path("status").asText("");
            if (status.equals("REJECTED")) anyRejected = true;
            if (status.equals("UPLOADING")) anyUploading = true;
            if (!status.equals("READY")) { allReady = false; anyOther = true; }
        }

        if (anyRejected) return "Rejected";
        if (anyUploading) return "Processing";
        if (allReady) return "Uploaded";
        if (allMissing) return "Not available";
        if (anyOther) return "Status unavailable";
        return "Incomplete";
    }

    private static String overview(String status) {
        switch (status) {
            case "VERIFIED": return "Verified";
            case "PENDING": return "Pending review";
            case "REJECTED": return "Verification rejected";
            case "": return "Verification status unavailable";
            default: {
                String text = words(status);
                return Character.toUpperCase(text.charAt(0)) + text.substring(1);
            }
        }
    }

    // ---------- Small helpers ----------

    private static String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    private static String words(String value) {
        return value.toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private static String pretty(String value) {
        String text = words(value);
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}