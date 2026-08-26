package com.anhviet.avsport.model;

import org.json.JSONException;
import org.json.JSONObject;

public class SourceValidationResult {
    public final String normalizedUrl;
    public final String sourceName;
    public final int itemCount;
    public final String rawJson;

    public SourceValidationResult(JSONObject json, String rawJson) {
        this.normalizedUrl = json.optString("normalizedUrl");
        this.sourceName = json.optString("sourceName", "Nguồn mới");
        this.itemCount = json.optInt("itemCount", 0);
        this.rawJson = rawJson;
    }

    public static SourceValidationResult fromJson(String rawJson) throws JSONException {
        return new SourceValidationResult(new JSONObject(rawJson), rawJson);
    }
}
