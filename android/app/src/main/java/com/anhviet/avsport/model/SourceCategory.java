package com.anhviet.avsport.model;

import org.json.JSONObject;

public class SourceCategory {
    public final String key;
    public final String label;
    public final String iconUrl;
    public final String badgeLabel;
    public final int itemCount;

    public SourceCategory(String key, String label, String iconUrl, String badgeLabel, int itemCount) {
        this.key = key;
        this.label = label;
        this.iconUrl = emptyToNull(iconUrl);
        this.badgeLabel = emptyToNull(badgeLabel);
        this.itemCount = itemCount;
    }

    public static SourceCategory fromJson(JSONObject json) {
        return new SourceCategory(
            json.optString("key"),
            json.optString("label"),
            json.optString("iconUrl", null),
            json.optString("badgeLabel", null),
            json.optInt("itemCount", 0)
        );
    }

    private static String emptyToNull(String value) {
        return value == null || value.length() == 0 || "null".equals(value) ? null : value;
    }
}
