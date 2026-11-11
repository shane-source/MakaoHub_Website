package models;

import java.util.ArrayList;
import java.util.List;

/** Plain values for the dashboard page. "—" means the backend did not give us that number. */
public class DashboardData {

    public boolean loaded = false;                 // false when the backend could not be reached
    public String verification = "NOT_STARTED";
    public String verificationLabel = "Not started";

    public String active = "—";
    public String pending = "—";
    public String drafts = "—";

    public boolean applicationsLoaded = false;
    public List<ApplicationRow> applications = new ArrayList<>();

    public boolean homesLoaded = false;
    public List<HomeCard> homes = new ArrayList<>();

    public static class ApplicationRow {
        public final String title;
        public final String status;

        public ApplicationRow(String title, String status) {
            this.title = title;
            this.status = status;
        }
    }

    public static class HomeCard {
        public final String title;
        public final String details;
        public final String rent;
        public final boolean sponsored;

        public HomeCard(String title, String details, String rent, boolean sponsored) {
            this.title = title;
            this.details = details;
            this.rent = rent;
            this.sponsored = sponsored;
        }
    }
}