package com.anhviet.avsport.model;

import org.json.JSONObject;

public class StreamOption {
    public final int index;
    public final String label;

    public StreamOption(int index, String label) {
        this.index = index;
        this.label = label == null || label.length() == 0 ? "Luồng " + (index + 1) : label;
    }

    public static StreamOption fromJson(JSONObject json) {
        return new StreamOption(json.optInt("index", 0), json.optString("label"));
    }
}
