package com.anhviet.avsport.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.anhviet.avsport.model.MediaSource;
import com.anhviet.avsport.model.SourceListResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SourceStore {
    private static final String PREFS_NAME = "av_sport_native";
    private static final String KEY_SOURCES = "sources";
    private static final String KEY_ACTIVE_SOURCE_ID = "active_source_id";
    private static final String KEY_ACTIVE_CATEGORIES = "active_categories";
    private static final String KEY_SOURCE_COUNTS = "source_counts";
    private static final String KEY_SOURCE_CACHE_PREFIX = "source_cache_";

    public static final String DEFAULT_SOURCE_ID = "seed-socolive";

    private final SharedPreferences prefs;

    public SourceStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensureDefaultSources();
    }

    public List<MediaSource> readSources() {
        List<MediaSource> sources = readRawSources();
        if (sources.isEmpty()) {
            sources = defaultSources();
            writeSources(sources);
        }
        return sources;
    }

    public void writeSources(List<MediaSource> sources) {
        JSONArray array = new JSONArray();
        if (sources != null) {
            for (MediaSource source : sources) {
                try {
                    array.put(source.toJson());
                } catch (JSONException ignored) {
                }
            }
        }
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply();
    }

    public String readActiveSourceId() {
        String activeSourceId = prefs.getString(KEY_ACTIVE_SOURCE_ID, null);
        if (activeSourceId != null && activeSourceId.length() > 0) {
            return activeSourceId;
        }
        return DEFAULT_SOURCE_ID;
    }

    public void writeActiveSourceId(String sourceId) {
        prefs.edit().putString(KEY_ACTIVE_SOURCE_ID, sourceId != null ? sourceId : DEFAULT_SOURCE_ID).apply();
    }

    public String readActiveCategoryKey(String sourceId) {
        if (sourceId == null || sourceId.length() == 0) {
            return null;
        }
        try {
            JSONObject json = new JSONObject(prefs.getString(KEY_ACTIVE_CATEGORIES, "{}"));
            String value = json.optString(sourceId, null);
            return value == null || value.length() == 0 ? null : value;
        } catch (JSONException ignored) {
            return null;
        }
    }

    public void writeActiveCategoryKey(String sourceId, String categoryKey) {
        if (sourceId == null || sourceId.length() == 0 || categoryKey == null || categoryKey.length() == 0) {
            return;
        }
        try {
            JSONObject json = new JSONObject(prefs.getString(KEY_ACTIVE_CATEGORIES, "{}"));
            json.put(sourceId, categoryKey);
            prefs.edit().putString(KEY_ACTIVE_CATEGORIES, json.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public Map<String, Integer> readCounts() {
        Map<String, Integer> counts = new HashMap<>();
        try {
            JSONObject json = new JSONObject(prefs.getString(KEY_SOURCE_COUNTS, "{}"));
            Iterator<String> keys = json.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                counts.put(key, json.optInt(key, 0));
            }
        } catch (JSONException ignored) {
        }
        return counts;
    }

    public void writeCount(String sourceId, int count) {
        if (sourceId == null || sourceId.length() == 0) {
            return;
        }
        Map<String, Integer> counts = readCounts();
        counts.put(sourceId, count);
        JSONObject json = new JSONObject();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            try {
                json.put(entry.getKey(), entry.getValue());
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY_SOURCE_COUNTS, json.toString()).apply();
    }

    public SourceListResult readCachedSource(String sourceId) {
        if (sourceId == null || sourceId.length() == 0) {
            return null;
        }
        String raw = prefs.getString(KEY_SOURCE_CACHE_PREFIX + sourceId, null);
        if (raw == null || raw.length() == 0) {
            return null;
        }
        try {
            return SourceListResult.fromJson(new JSONObject(raw));
        } catch (JSONException ignored) {
            return null;
        }
    }

    public void writeCachedSource(String sourceId, SourceListResult result) {
        if (sourceId == null || sourceId.length() == 0 || result == null) {
            return;
        }
        try {
            prefs.edit().putString(KEY_SOURCE_CACHE_PREFIX + sourceId, result.toJson().toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    public void clearCachedSource(String sourceId) {
        if (sourceId == null || sourceId.length() == 0) {
            return;
        }
        prefs.edit().remove(KEY_SOURCE_CACHE_PREFIX + sourceId).apply();
    }

    public void removeSourceData(String sourceId) {
        if (sourceId == null || sourceId.length() == 0) {
            return;
        }
        clearCachedSource(sourceId);
        prefs.edit()
            .putString(KEY_ACTIVE_CATEGORIES, removeJsonKey(prefs.getString(KEY_ACTIVE_CATEGORIES, "{}"), sourceId))
            .putString(KEY_SOURCE_COUNTS, removeJsonKey(prefs.getString(KEY_SOURCE_COUNTS, "{}"), sourceId))
            .apply();
    }

    private String removeJsonKey(String rawJson, String key) {
        try {
            JSONObject json = new JSONObject(rawJson);
            json.remove(key);
            return json.toString();
        } catch (JSONException ignored) {
            return "{}";
        }
    }

    private void ensureDefaultSources() {
        List<MediaSource> sources = readRawSources();
        boolean changed = migrateKnownSourceUrls(sources);
        
        boolean hasSoco = false;
        for (MediaSource s : sources) {
            if (DEFAULT_SOURCE_ID.equals(s.id)) {
                hasSoco = true;
                break;
            }
        }
        if (!hasSoco) {
            sources.add(0, new MediaSource(DEFAULT_SOURCE_ID, "Socolive Trực Tiếp", "socolive://live"));
            changed = true;
        }

        if (changed || sources.isEmpty()) {
            if (sources.isEmpty()) {
                sources = defaultSources();
            }
            writeSources(sources);
            writeActiveSourceId(DEFAULT_SOURCE_ID);
        }
    }

    private boolean migrateKnownSourceUrls(List<MediaSource> sources) {
        boolean changed = false;

        // Purge dead built-in seeds
        for (int index = sources.size() - 1; index >= 0; index--) {
            MediaSource source = sources.get(index);
            if (source.id.startsWith("seed-") && !DEFAULT_SOURCE_ID.equals(source.id)) {
                clearCachedSource(source.id);
                sources.remove(index);
                changed = true;
            } else if (DEFAULT_SOURCE_ID.equals(source.id)) {
                if (!"socolive://live".equals(source.url)) {
                    sources.set(index, new MediaSource(DEFAULT_SOURCE_ID, "Socolive Trực Tiếp", "socolive://live"));
                    clearCachedSource(source.id);
                    changed = true;
                }
            } else {
                String migratedUrl = migrateKnownSourceUrl(source.url);
                if (migratedUrl.length() == 0) {
                    clearCachedSource(source.id);
                    sources.remove(index);
                    changed = true;
                } else if (!migratedUrl.equals(source.url)) {
                    sources.set(index, new MediaSource(source.id, source.name, migratedUrl));
                    clearCachedSource(source.id);
                    changed = true;
                }
            }
        }

        return changed;
    }

    private String migrateKnownSourceUrl(String sourceUrl) {
        if (sourceUrl == null) {
            return "";
        }
        if (sourceUrl.contains("s2sprediction.net") || sourceUrl.contains("socolive")) {
            return "socolive://live";
        }
        if (isKnownDeadSourceUrl(sourceUrl)) {
            return "";
        }
        return sourceUrl;
    }

    private boolean isKnownDeadSourceUrl(String sourceUrl) {
        if (sourceUrl == null || sourceUrl.isEmpty()) {
            return false;
        }
        return sourceUrl.matches("(?i)https?://(www\\.)?(sensorrx\\.io|qlick\\.io|perguidex\\.io|cakhiazkz\\.cc|bunchatv\\d*\\.net|thapcam24h\\.net|lithiumvalleycommunitycoalition\\.org|xoilac.*|90phut.*|gavang.*)/?");
    }

    private List<MediaSource> readRawSources() {
        List<MediaSource> sources = new ArrayList<>();
        String raw = prefs.getString(KEY_SOURCES, null);
        if (raw == null) {
            return sources;
        }
        try {
            JSONArray array = new JSONArray(raw);
            for (int index = 0; index < array.length(); index++) {
                MediaSource source = MediaSource.fromJson(array.getJSONObject(index));
                if (source.id.length() > 0 && source.name.length() > 0 && source.url.length() > 0) {
                    sources.add(source);
                }
            }
        } catch (JSONException ignored) {
        }
        return sources;
    }

    public static List<MediaSource> defaultSources() {
        List<MediaSource> sources = new ArrayList<>();
        sources.add(new MediaSource(DEFAULT_SOURCE_ID, "Socolive Trực Tiếp", "socolive://live"));
        return sources;
    }
}
