package com.anhviet.avsport.model;

import org.json.JSONObject;

public class AppUpdateInfo {
    public final String appName;
    public final String version;
    public final int versionCode;
    public final String apkUrl;
    public final String publishedAt;
    public final boolean required;

    public AppUpdateInfo(String appName, String version, int versionCode, String apkUrl, String publishedAt, boolean required) {
        this.appName = appName;
        this.version = version;
        this.versionCode = versionCode;
        this.apkUrl = apkUrl;
        this.publishedAt = publishedAt;
        this.required = required;
    }

    public static AppUpdateInfo fromJson(String rawJson) throws Exception {
        JSONObject json = new JSONObject(rawJson);
        return new AppUpdateInfo(
            json.optString("appName", "AV Sport"),
            json.optString("version", ""),
            json.optInt("versionCode", 0),
            json.optString("apkUrl", ""),
            json.optString("publishedAt", ""),
            json.optBoolean("required", false)
        );
    }
}
