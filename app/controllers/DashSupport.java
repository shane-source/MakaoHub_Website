package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import models.DashboardData;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;
import services.AuthedApi;

/** Small helpers shared by the dashboard pages. */
final class DashSupport {

    private static final Set<String> ROLES = Set.of("RENTER", "LANDLORD", "SUBLESSOR");
    private static final DateTimeFormatter KENYA =
            DateTimeFormatter.ofPattern("d MMM yyyy · h:mm a 'EAT'", Locale.ENGLISH)
                    .withZone(ZoneId.of("Africa/Nairobi"));

    private DashSupport() {}

    /** The signed-in person's role, or null when they are signed out or the role is unknown. */
    static String roleOf(Http.Request request) {
        boolean signedIn = request.session().get("accessToken").isPresent();
        String role = request.session().get("role").orElse("");
        return signedIn && ROLES.contains(role) ? role : null;
    }

    static Result toSignIn() {
        return Results.redirect("/get-started?mode=login").withNewSession();
    }

    static CompletionStage<Result> done(Result result) {
        return CompletableFuture.completedFuture(result);
    }

    /** Saves refreshed tokens into the session when the backend renewed them. */
    static Result applyTokens(Result page, AuthedApi.Batch batch, Http.Request request) {
        if (!batch.refreshed()) return page;
        Map<String, String> session = new HashMap<>(request.session().data());
        session.put("accessToken", batch.accessToken);
        session.put("refreshToken", batch.refreshToken);
        return page.withSession(session);
    }

    /** 20 items at first, then 20 more each time, never more than 200. */
    static int limit(Integer show) {
        int value = show == null ? 20 : show;
        return Math.max(20, Math.min(value, 200));
    }

    static String pretty(String value) {
        if (value == null || value.isEmpty()) return "";
        String text = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    static String kes(long value) {
        return "KSh " + String.format(Locale.UK, "%,d", value);
    }

    /** Kenya time, as the Android app shows it. */
    static String time(String value) {
        try {
            return KENYA.format(OffsetDateTime.parse(value).toInstant());
        } catch (Exception e) {
            return "Time unavailable";
        }
    }

    /** "Boosted · 3 days left" while the boost has not expired, otherwise null. */
    static String boostLabel(String expiresAt) {
        try {
            Instant expires = OffsetDateTime.parse(expiresAt).toInstant();
            Instant now = Instant.now();
            if (!expires.isAfter(now)) return null;
            long days = Duration.between(now, expires).toDays();
            if (days == 0) return "Boosted · < 1 day left";
            return "Boosted · " + days + (days == 1 ? " day" : " days") + " left";
        } catch (Exception e) {
            return null;
        }
    }

    /** "approved" and similar read green, "rejected" red, "waiting" amber. */
    static String statusClass(String status) {
        switch (status) {
            case "APPROVED":
            case "ACCEPTED":
            case "VERIFIED":
                return "ok";
            case "REJECTED":
            case "WITHDRAWN":
            case "DECLINED":
                return "bad";
            case "SUBMITTED":
            case "UNDER_REVIEW":
                return "warn";
            default:
                return "";
        }
    }

    /** Cards for one page of /renter/homes. */
    static List<DashboardData.HomeCard> parseHomes(JsonNode page) {
        List<DashboardData.HomeCard> cards = new ArrayList<>();
        if (page == null || !page.path("items").isArray()) return cards;
        for (JsonNode home : page.path("items")) {
            List<String> parts = new ArrayList<>();
            String area = home.path("areaLabel").asText("");
            if (!area.isEmpty()) parts.add(area);
            if (home.hasNonNull("bedroomCount")) {
                int beds = home.path("bedroomCount").asInt();
                parts.add(beds + (beds == 1 ? " Bed" : " Beds"));
            }
            int baths = home.path("bathroomCount").asInt();
            parts.add(baths + (baths == 1 ? " Bath" : " Baths"));
            cards.add(new DashboardData.HomeCard(
                    home.path("title").asText("Home"),
                    String.join(" · ", parts),
                    kes(home.path("monthlyRentKes").asLong()) + "/mo",
                    home.path("sponsored").asBoolean(false)));
        }
        return cards;
    }
}