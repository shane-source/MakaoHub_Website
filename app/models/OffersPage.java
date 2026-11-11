package models;

import java.util.ArrayList;
import java.util.List;

public class OffersPage {

    public boolean loaded = false;
    public boolean boostMode = false;
    public boolean pending = false;          // a Paystack payment is waiting to be checked
    public String notice = "";
    public String error = "";
    public int allowance = 1;
    public List<String> active = new ArrayList<>();
    public List<Offer> offers = new ArrayList<>();
    public List<Insight> insights = new ArrayList<>();
    public String portfolio = "";
    public List<BoostListing> listings = new ArrayList<>();

    public static class Offer {
        public final String code;
        public final String title;
        public final String terms;
        public final String detail;

        public Offer(String code, String title, String terms, String detail) {
            this.code = code;
            this.title = title;
            this.terms = terms;
            this.detail = detail;
        }
    }

    public static class Insight {
        public final String title;
        public final long opens;
        public final long enquiries;

        public Insight(String title, long opens, long enquiries) {
            this.title = title;
            this.opens = opens;
            this.enquiries = enquiries;
        }
    }

    public static class BoostListing {
        public final String id;
        public final String title;
        public final String rent;
        public final String active;          // empty when there is no active boost
        public final List<Offer> options;

        public BoostListing(String id, String title, String rent, String active, List<Offer> options) {
            this.id = id;
            this.title = title;
            this.rent = rent;
            this.active = active;
            this.options = options;
        }
    }
}