package com.anhviet.avsport.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class SourceListResult {
    public final String sourceName;
    public final List<SourceCategory> categories;
    public final List<MediaCard> items;
    public final String rawJson;

    public SourceListResult(String sourceName, List<SourceCategory> categories, List<MediaCard> items, String rawJson) {
        this.sourceName = sourceName;
        this.categories = categories;
        this.items = items;
        this.rawJson = rawJson;
    }

    public static SourceListResult fromJson(String rawJson) throws JSONException {
        JSONObject json = new JSONObject(rawJson);
        JSONArray categoryArray = json.optJSONArray("categories");
        JSONArray itemArray = json.optJSONArray("items");
        List<SourceCategory> categories = new ArrayList<>();
        List<MediaCard> items = new ArrayList<>();

        if (categoryArray != null) {
            for (int index = 0; index < categoryArray.length(); index++) {
                categories.add(SourceCategory.fromJson(categoryArray.getJSONObject(index)));
            }
        }

        if (itemArray != null) {
            for (int index = 0; index < itemArray.length(); index++) {
                items.add(new MediaCard(itemArray.getJSONObject(index)));
            }
        }

        return new SourceListResult(json.optString("sourceName"), categories, items, rawJson);
    }
}
