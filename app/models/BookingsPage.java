package models;

import java.util.ArrayList;
import java.util.List;

public class BookingsPage {

    public boolean loaded = false;
    public String notice = "";
    public String error = "";

    public boolean destLoaded = false;
    public boolean destConfigured = false;
    public boolean destEligible = false;

    public List<Row> bookings = new ArrayList<>();

    public boolean paymentsLoaded = false;
    public List<PayRow> payments = new ArrayList<>();
    public int page = 0;
    public boolean hasNext = false;

    public static class Row {
        public final String renter;
        public final String owner;
        public final String status;
        public final String money;
        public final String response;
        public final String ownerConfirm;
        public final String waitEnds;
        public final String falseNote;       // empty unless the renter answered False
        public final String payout;          // empty when the backend gave no payout status
        public final String action;          // form address for "Renter moved in"; empty when not available

        public Row(String renter, String owner, String status, String money, String response,
                   String ownerConfirm, String waitEnds, String falseNote, String payout, String action) {
            this.renter = renter;
            this.owner = owner;
            this.status = status;
            this.money = money;
            this.response = response;
            this.ownerConfirm = ownerConfirm;
            this.waitEnds = waitEnds;
            this.falseNote = falseNote;
            this.payout = payout;
            this.action = action;
        }
    }

    public static class PayRow {
        public final String title;
        public final String status;
        public final String statusClass;
        public final String amount;
        public final String receipt;

        public PayRow(String title, String status, String statusClass, String amount, String receipt) {
            this.title = title;
            this.status = status;
            this.statusClass = statusClass;
            this.amount = amount;
            this.receipt = receipt;
        }
    }
}