package controllers;

import com.typesafe.config.Config;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

public class HelpController extends Controller {

    private static final Set<String> ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");

    /** One help topic. Its link opens WhatsApp with the message already typed. */
    public record Topic(String label, String note, String url) {}

    public record HelpView(boolean configured, String number, String chatUrl, List<Topic> topics) {}

    private final String number;

    @Inject
    public HelpController(Config config) {
        String value = config.hasPath("makao.support.whatsapp") ? config.getString("makao.support.whatsapp") : "";
        value = value.replaceAll("[^0-9]", "");
        // Only a plausible international number is used. Anything else counts as "not set up".
        this.number = value.matches("[0-9]{9,15}") ? value : "";
    }

    public Result index(Http.Request request) {
        boolean signedIn = request.session().get("accessToken").isPresent();
        String role = request.session().get("role").orElse("");
        if (!signedIn || !ROLES.contains(role)) {
            return redirect("/get-started?mode=login");
        }

        String who = "a " + role.toLowerCase(Locale.ROOT);

        List<Topic> topics = new ArrayList<>();
        topics.add(topic(who, "Verification", "Documents, review and your badge", "verifying my account"));
        topics.add(topic(who, "Payments and payouts", "Bookings, deposits and payout numbers", "a payment or payout"));
        topics.add(topic(who, "Listings", "Creating, editing or boosting a listing", "my listings"));
        topics.add(topic(who, "Sign-in and account", "Codes, passwords and account changes", "signing in or my account"));
        topics.add(topic(who, "Something else", "Tell us what happened", "something else"));

        String general = link("Hello Makao Hub support. I'm " + who + " and I need help.");

        HelpView view = new HelpView(!number.isEmpty(), number.isEmpty() ? "" : "+" + number, general, topics);
        return ok(views.html.help.render(role, view, request));
    }

    private Topic topic(String who, String label, String note, String about) {
        String text = "Hello Makao Hub support. I'm " + who + " and I need help with " + about + ".";
        return new Topic(label, note, link(text));
    }

    /** A WhatsApp link with the message typed in. It never includes a token, email address or id. */
    private String link(String text) {
        if (number.isEmpty()) return "";
        return "https://wa.me/" + number + "?text=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
    }
}