package com.anhviet.avsport.data;

import com.anhviet.avsport.BuildConfig;
import com.anhviet.avsport.model.AppUpdateInfo;
import com.anhviet.avsport.model.SourceListResult;
import com.anhviet.avsport.model.SourceValidationResult;
import com.anhviet.avsport.model.StreamResult;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class ApiClient {
    private final String baseUrl;
    private final String updateManifestUrl;

    public ApiClient() {
        baseUrl = trimTrailingSlash(BuildConfig.API_BASE_URL);
        updateManifestUrl = BuildConfig.UPDATE_MANIFEST_URL;
    }

    public SourceListResult fetchSourceList(String sourceUrl, boolean forceRefresh) throws Exception {
        String endpoint = "/api/list?url=" + encode(sourceUrl);
        if (forceRefresh) {
            endpoint += "&refresh=1";
        }

        return SourceListResult.fromJson(get(endpoint));
    }

    public SourceValidationResult validateSource(String sourceUrl) throws Exception {
        return SourceValidationResult.fromJson(get("/api/validate-source?url=" + encode(sourceUrl)));
    }

    public StreamResult fetchStream(String pageUrl, int streamIndex) throws Exception {
        String endpoint = "/api/stream?url=" + encode(pageUrl) + "&streamIndex=" + streamIndex;
        return StreamResult.fromJson(get(endpoint));
    }

    public AppUpdateInfo fetchAppUpdate() throws Exception {
        if (updateManifestUrl != null && updateManifestUrl.startsWith("http")) {
            return AppUpdateInfo.fromJson(getAbsolute(updateManifestUrl));
        }

        return AppUpdateInfo.fromJson(get("/app-update.json"));
    }

    public String toAbsoluteUrl(String candidate) {
        if (candidate == null || candidate.length() == 0) {
            return candidate;
        }

        if (candidate.startsWith("http://") || candidate.startsWith("https://")) {
            return candidate;
        }

        if (candidate.startsWith("/")) {
            return baseUrl + candidate;
        }

        return baseUrl + "/" + candidate;
    }

    private String get(String endpoint) throws IOException {
        return getAbsolute(baseUrl + endpoint);
    }

    private String getAbsolute(String targetUrl) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(targetUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "AV Sport Android TV/" + BuildConfig.VERSION_NAME);

            int statusCode = connection.getResponseCode();
            InputStream stream = statusCode >= 200 && statusCode < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
            String body = readStream(stream);

            if (statusCode < 200 || statusCode >= 300) {
                throw new IOException(extractError(body, statusCode));
            }

            return body;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readStream(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line);
            }
        }

        return builder.toString();
    }

    private static String extractError(String body, int statusCode) {
        try {
            JSONObject json = new JSONObject(body);
            String error = json.optString("error");
            if (error != null && error.length() > 0) {
                return error;
            }
        } catch (Exception ignored) {
            // Fall through to generic network message.
        }

        return "Máy chủ trả về lỗi " + statusCode;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception error) {
            return value;
        }
    }

    private static String trimTrailingSlash(String value) {
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
