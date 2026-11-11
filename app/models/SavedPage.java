package models;

import java.util.ArrayList;
import java.util.List;

public class SavedPage {
    public boolean loaded = false;
    public List<DashboardData.HomeCard> homes = new ArrayList<>();
    public int page = 0;
    public boolean hasNext = false;
}