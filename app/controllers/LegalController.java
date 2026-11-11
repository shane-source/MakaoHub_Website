package controllers;

import java.util.Set;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

public class LegalController extends Controller {

    private static final Set<String> ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");

    /** Public pages, linked from the footer. */
    public Result terms() {
        return ok(views.html.terms.render());
    }

    public Result privacy() {
        return ok(views.html.privacy.render());
    }

    /** The signed-in "Privacy and account" page. */
    public Result account(Http.Request request) {
        boolean signedIn = request.session().get("accessToken").isPresent();
        String role = request.session().get("role").orElse("");
        if (!signedIn || !ROLES.contains(role)) {
            return redirect("/get-started?mode=login");
        }
        return ok(views.html.privacyAccount.render(role, request));
    }
}