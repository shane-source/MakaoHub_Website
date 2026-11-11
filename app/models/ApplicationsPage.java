package models;

import java.util.ArrayList;
import java.util.List;

public class ApplicationsPage {

    public boolean loaded = false;          // false: show "unavailable"
    public boolean hiddenView = false;
    public int fresh = 0;
    public int underReview = 0;
    public int approved = 0;
    public int hiddenCount = 0;
    public List<Row> rows = new ArrayList<>();
    public boolean hasMore = false;
    public int nextShow = 40;

    public boolean bookingsLoaded = false;  // renters only
    public List<BookingRow> bookings = new ArrayList<>();

    public static class Row {
        public final String title;
        public final String applicant;      // owners only
        public final boolean verified;
        public final String status;
        public final String statusClass;
        public final String submitted;
        public final String reason;         // null when there is none

        public Row(String title, String applicant, boolean verified, String status,
                   String statusClass, String submitted, String reason) {
            this.title = title;
            this.applicant = applicant;
            this.verified = verified;
            this.status = status;
            this.statusClass = statusClass;
            this.submitted = submitted;
            this.reason = reason;
        }
    }

    public static class BookingRow {
        public final String who;
        public final String kind;
        public final String status;
        public final String total;

        public BookingRow(String who, String kind, String status, String total) {
            this.who = who;
            this.kind = kind;
            this.status = status;
            this.total = total;
        }
    }
}