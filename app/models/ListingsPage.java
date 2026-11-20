package models;

import java.util.ArrayList;
import java.util.List;

public class ListingsPage {

    public boolean loaded = false;
    public String filter = "PUBLISHED";
    public String filterLabel = "active";
    public int active = 0;
    public int drafts = 0;
    public int paused = 0;
    public int total = 0;
    public List<ListingRow> rows = new ArrayList<>();
    public boolean hasMore = false;
    public int nextShow = 40;

    public static class ListingRow {
        public final String id;          // empty when the backend gave an id we won't put in an address
        public final String title;
        public final String rent;
        public final String details;
        public final String status;
        public final String statusLabel;
        public final String available;   // null when not set
        public final int leads;
        public final String views;
        public final String boost;       // null when there is no active boost
        public String photo = "";        // signed https address of the first photo, empty when none

        public ListingRow(String id, String title, String rent, String details, String status,
                          String statusLabel, String available, int leads, String views, String boost) {
            this.id = id;
            this.title = title;
            this.rent = rent;
            this.details = details;
            this.status = status;
            this.statusLabel = statusLabel;
            this.available = available;
            this.leads = leads;
            this.views = views;
            this.boost = boost;
        }
    }
}