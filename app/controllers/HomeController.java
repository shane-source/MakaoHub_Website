package controllers;

import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

public class HomeController extends Controller {

    public Result index(Http.Request request) {
        // Existing user (we know their last role) and not signed in: straight to sign-in as that role.
        // New visitor: the welcome page.
        String last = DashSupport.lastRole(request);
        if (last != null && request.session().get("accessToken").isEmpty()) {
            return redirect("/login?role=" + last);
        }
        return ok(views.html.index.render());
    }
}