package models;

import java.util.ArrayList;
import java.util.List;

/** What the Sublessor "Roommates" page shows: interests in their spaces and opted-in renters. */
public class RoommatesPage {

    public boolean interestsLoaded = false;
    public boolean discoveryLoaded = false;
    public boolean hasPreferences = false;
    public boolean optedIn = false;
    public String summary = "";
    public List<Interest> interests = new ArrayList<>();
    public List<Person> people = new ArrayList<>();
    public int page = 0;
    public boolean hasNext = false;

    public static class Interest {
        public final String id;
        public final String listing;
        public final String status;
        public final String statusClass;
        public final boolean open;       // can still be accepted or declined
        public final boolean accepted;

        public Interest(String id, String listing, String status, String statusClass, boolean open, boolean accepted) {
            this.id = id;
            this.listing = listing;
            this.status = status;
            this.statusClass = statusClass;
            this.open = open;
            this.accepted = accepted;
        }
    }

    public static class Person {
        public final String id;
        public final String name;
        public final String area;
        public final String budget;
        public final String traits;

        public Person(String id, String name, String area, String budget, String traits) {
            this.id = id;
            this.name = name;
            this.area = area;
            this.budget = budget;
            this.traits = traits;
        }
    }

    /** One preference question with its choices, for the editing form. */
    public static class Question {
        public final String field;
        public final String title;
        public final List<String[]> options;   // {key, label}
        public final String selected;

        public Question(String field, String title, List<String[]> options, String selected) {
            this.field = field;
            this.title = title;
            this.options = options;
            this.selected = selected;
        }
    }

    public static class PreferencesForm {
        public List<Question> questions = new ArrayList<>();
        public String minBudget = "";
        public String maxBudget = "";
        public boolean optIn = false;
        public boolean loaded = false;
    }

    /** One opted-in renter in full, compared with the sublessor's own answers. */
    public static class Profile {
        public String name = "";
        public String area = "";
        public String about = "";
        public String employment = "";
        public String moveIn = "";
        public String budget = "";
        public List<String> same = new ArrayList<>();
        public List<String> different = new ArrayList<>();
        public boolean loaded = false;
    }
}
