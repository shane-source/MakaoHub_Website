package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import play.libs.Json;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.AuthedApi;

/** Renters report a listing. Reports are private and reviewed by staff. */
public class ReportController extends Controller {

    private static final Set<String> CATEGORIES =
            Set.of("MISLEADING_CONTENT", "PAYMENT_REQUEST", "UNSAFE_CONTENT", "OTHER");

    private final AuthedApi api;

    @Inject
    public ReportController(AuthedApi api) {
        this.api = api;
    }

    private static String kindOf(String source) {
        if ("LANDLORD".equals(source)) return "LANDLORD_LISTING";
        if ("SUBLESSOR".equals(source)) return "SHARED_LISTING";
        return null;
    }

    public CompletionStage<Result> page(Http.Request request, String source, String id) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());
        if (!role.equals("RENTER") || kindOf(source) == null || !id.matches("[A-Za-z0-9-]{1,64}")) {
            return DashSupport.done(redirect("/dashboard/homes"));
        }
        return DashSupport.done(ok(views.html.report.render(role, source, id, request)));
    }

    public CompletionStage<Result> submit(Http.Request request, String source, String id) {
        String role = DashSupport.roleOf(request);
        if (role == null) return DashSupport.done(DashSupport.toSignIn());
        String kind = kindOf(source);
        if (!role.equals("RENTER") || kind == null || !id.matches("[A-Za-z0-9-]{1,64}")) {
            return DashSupport.done(redirect("/dashboard/homes"));
        }

        Map<String, String[]> form = request.body().asFormUrlEncoded();
        String category = value(form, "category");
        String description = value(form, "description");
        String back = "/dashboard/homes/" + source + "/" + id + "/report";
        if (!CATEGORIES.contains(category) || description.isEmpty() || description.length() > 1000) {
            return DashSupport.done(redirect(back).flashing("rp", "invalid"));
        }

        ObjectNode body = Json.newObject();
        body.put("subjectKind", kind);
        body.put("subjectId", id);
        body.put("category", category);
        body.put("description", description);

        return api.post(request, "/api/v1/reports", body).thenApply(r -> {
            if (r.expired) return DashSupport.toSignIn();
            Result result = r.ok()
                    ? redirect("/dashboard/homes/" + source + "/" + id).flashing("hm", "reported")
                    : redirect(back).flashing("rp", "failed");
            if (r.accessToken != null) {
                java.util.Map<String, String> session = new java.util.HashMap<>(request.session().data());
                session.put("accessToken", r.accessToken);
                session.put("refreshToken", r.refreshToken);
                result = result.withSession(session);
            }
            return result;
        });
    }

    private static String value(Map<String, String[]> form, String name) {
        if (form == null || form.get(name) == null || form.get(name).length == 0) return "";
        return form.get(name)[0].trim();
    }
}
