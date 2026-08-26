package com.anhviet.avsport.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class StreamResult {
    public final String title;
    public final String playbackUrl;
    public final String streamUrl;
    public final String type;
    public final List<StreamOption> options;
    public final int selectedStreamIndex;

    public StreamResult(JSONObject json) throws JSONException {
        title = json.optString("title", "Trực tiếp");
        playbackUrl = emptyToNull(json.optString("playbackUrl", null));
        streamUrl = json.optString("streamUrl");
        type = json.optString("type", "file");
        selectedStreamIndex = json.optInt("selectedStreamIndex", 0);
        options = new ArrayList<>();

        JSONArray optionArray = json.optJSONArray("options");
        if (optionArray != null) {
            for (int index = 0; index < optionArray.length(); index++) {
                options.add(StreamOption.fromJson(optionArray.getJSONObject(index)));
            }
        }

        if (options.isEmpty()) {
            options.add(new StreamOption(0, "Live"));
        }
    }

    public static StreamResult fromJson(String rawJson) throws JSONException {
        return new StreamResult(new JSONObject(rawJson));
    }

    public String bestUrl() {
        return playbackUrl != null ? playbackUrl : streamUrl;
    }

    private static String emptyToNull(String value) {
        return value == null || value.length() == 0 || "null".equals(value) ? null : value;
    }
}
