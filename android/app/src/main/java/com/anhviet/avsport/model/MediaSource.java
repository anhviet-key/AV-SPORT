package com.anhviet.avsport.model;

import org.json.JSONException;
import org.json.JSONObject;

public class MediaSource {
    public final String id;
    public final String name;
    public final String url;

    public MediaSource(String id, String name, String url) {
        this.id = id;
        this.name = name;
        this.url = url;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("url", url);
        return json;
    }

    public static MediaSource fromJson(JSONObject json) {
        return new MediaSource(
            json.optString("id"),
            json.optString("name"),
            json.optString("url")
        );
    }
}
