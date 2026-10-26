package com.anhviet.avsport.data;

import com.anhviet.avsport.BuildConfig;
import com.anhviet.avsport.model.AppUpdateInfo;
import com.anhviet.avsport.model.MediaCard;
import com.anhviet.avsport.model.SourceCategory;
import com.anhviet.avsport.model.SourceListResult;
import com.anhviet.avsport.model.SourceValidationResult;
import com.anhviet.avsport.model.StreamOption;
import com.anhviet.avsport.model.StreamResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ApiClient {
    private static final String DEFAULT_REFERER = "https://4socolive.tv/";
    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Pattern STREAM_ROOM_PATTERN = Pattern.compile("stream-(\\d+)");

    private final String baseUrl;
    private final String updateManifestUrl;
    private final SimpleDateFormat timeFormat;

    public ApiClient() {
        baseUrl = trimTrailingSlash(BuildConfig.API_BASE_URL);
        updateManifestUrl = BuildConfig.UPDATE_MANIFEST_URL;
        timeFormat = new SimpleDateFormat("HH:mm dd/MM", Locale.getDefault());
        timeFormat.setTimeZone(TimeZone.getTimeZone("GMT+7"));
    }

    public static boolean isSocoliveSource(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("soco")
            || lower.contains("socolive")
            || lower.contains("vnres")
            || lower.contains("s8tv")
            || lower.contains("s2sprediction");
    }

    public static boolean isSocoliveStream(String pageUrl) {
        if (pageUrl == null) {
            return false;
        }
        String lower = pageUrl.toLowerCase(Locale.ROOT);
        return lower.startsWith("socolive://")
            || lower.contains("source=socolive")
            || lower.contains("source%3dsocolive")
            || lower.contains("vnres")
            || lower.contains("s8tv");
    }

    public SourceListResult fetchSourceList(String sourceUrl, boolean forceRefresh) throws Exception {
        if (isSocoliveSource(sourceUrl)) {
            return fetchSocoliveList();
        }

        String endpoint = "/api/list?url=" + encode(sourceUrl);
        if (forceRefresh) {
            endpoint += "&refresh=1";
        }

        return SourceListResult.fromJson(get(endpoint));
    }

    public SourceValidationResult validateSource(String sourceUrl) throws Exception {
        if (isSocoliveSource(sourceUrl)) {
            SourceListResult list = fetchSocoliveList();
            JSONObject json = new JSONObject();
            json.put("normalizedUrl", "socolive://live");
            json.put("sourceName", "Socolive Trực Tiếp");
            json.put("itemCount", list.items.size());
            return new SourceValidationResult(json, json.toString());
        }

        return SourceValidationResult.fromJson(get("/api/validate-source?url=" + encode(sourceUrl)));
    }

    public StreamResult fetchStream(String pageUrl, int streamIndex) throws Exception {
        if (isSocoliveStream(pageUrl)) {
            return resolveSocoliveStream(pageUrl, streamIndex);
        }

        String endpoint = "/api/stream?url=" + encode(pageUrl) + "&streamIndex=" + streamIndex;
        return StreamResult.fromJson(get(endpoint));
    }

    public AppUpdateInfo fetchAppUpdate() throws Exception {
        if (updateManifestUrl != null && updateManifestUrl.startsWith("http")) {
            return AppUpdateInfo.fromJson(getAbsolute(updateManifestUrl, null));
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

    private SourceListResult fetchSocoliveList() {
        Map<String, String> livingStreams = fetchLivingStreams();
        JSONArray itemArray = new JSONArray();
        Set<String> seenIds = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();

        // 1. Fetch recommended matches
        try {
            String recRaw = getAbsolute("https://json.vnres.co/match_recommend.json", DEFAULT_REFERER);
            String recJson = stripJsonp(recRaw);
            JSONObject recObj = new JSONObject(recJson);
            JSONObject dataObj = recObj.optJSONObject("data");
            if (dataObj != null) {
                JSONArray matches = dataObj.optJSONArray("matches");
                if (matches != null) {
                    for (int i = 0; i < matches.length(); i++) {
                        JSONObject m = matches.getJSONObject(i);
                        String scheduleId = String.valueOf(m.optLong("scheduleId", m.optLong("matchId", 0)));
                        if (scheduleId.equals("0") || seenIds.contains(scheduleId)) {
                            continue;
                        }
                        seenIds.add(scheduleId);

                        String hostName = m.optString("hostName", "").trim();
                        String guestName = m.optString("guestName", "").trim();
                        String matchTitle = hostName.length() > 0 && guestName.length() > 0 ? hostName + " - " + guestName : "Trận đấu";
                        seenTitles.add(hostName.toLowerCase(Locale.ROOT));

                        int categoryId = m.optInt("categoryId", 1);
                        String categoryName = m.optString("categoryName", "");
                        String subCateName = m.optString("subCateName", "");
                        String sportKey = categoryId == 1 || categoryName.toLowerCase(Locale.ROOT).contains("bóng đá")
                            ? "football"
                            : (categoryId == 2 || categoryName.toLowerCase(Locale.ROOT).contains("bóng rổ") ? "basketball" : "other");

                        long matchTimeMs = m.optLong("matchTime", 0);
                        String timeLabel = matchTimeMs > 0 ? formatEpoch(matchTimeMs) : "";

                        JSONArray anchors = m.optJSONArray("anchors");
                        boolean hasLiveStream = false;
                        JSONArray anchorPayload = new JSONArray();

                        if (anchors != null) {
                            for (int a = 0; a < anchors.length(); a++) {
                                JSONObject anchorObj = anchors.getJSONObject(a);
                                String uid = String.valueOf(anchorObj.optLong("uid", 0));
                                String nick = anchorObj.optString("nickName", "BLV");
                                JSONObject roomObj = anchorObj.optJSONObject("anchor");
                                String room = roomObj != null ? roomObj.optString("roomNum", uid) : uid;

                                boolean isAnchorLive = livingStreams.containsKey(uid) || livingStreams.containsKey(room);
                                if (isAnchorLive) {
                                    hasLiveStream = true;
                                }

                                JSONObject aOpt = new JSONObject();
                                aOpt.put("uid", uid);
                                aOpt.put("nick", nick);
                                aOpt.put("room", room);
                                aOpt.put("isLive", isAnchorLive);
                                anchorPayload.put(aOpt);
                            }
                        }

                        String statusLabel = hasLiveStream ? "Trực tiếp" : "Sắp diễn ra";

                        JSONObject payload = new JSONObject();
                        payload.put("source", "socolive");
                        payload.put("id", scheduleId);
                        payload.put("title", matchTitle);
                        payload.put("sportKey", sportKey);
                        payload.put("anchors", anchorPayload);

                        JSONObject card = new JSONObject();
                        card.put("id", scheduleId);
                        card.put("title", matchTitle);
                        card.put("subtitle", subCateName);
                        card.put("pageUrl", "socolive://match?data=" + encode(payload.toString()));
                        card.put("sportKey", sportKey);
                        card.put("homeTeam", hostName);
                        card.put("awayTeam", guestName);
                        card.put("homeLogo", m.optString("hostIcon", null));
                        card.put("awayLogo", m.optString("guestIcon", null));
                        card.put("leagueLabel", subCateName.length() > 0 ? subCateName : categoryName);
                        card.put("timeLabel", timeLabel);
                        card.put("statusLabel", statusLabel);

                        itemArray.put(card);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 2. Fetch all live rooms
        try {
            String roomsRaw = getAbsolute("https://json.vnres.co/all_live_rooms.json", DEFAULT_REFERER);
            String roomsJson = stripJsonp(roomsRaw);
            JSONObject roomsObj = new JSONObject(roomsJson);
            JSONObject dataObj = roomsObj.optJSONObject("data");
            if (dataObj != null) {
                JSONArray rooms = dataObj.optJSONArray("0");
                if (rooms != null) {
                    for (int r = 0; r < rooms.length(); r++) {
                        JSONObject rm = rooms.getJSONObject(r);
                        String roomNum = rm.optString("roomNum", "");
                        String id = "room_" + roomNum;
                        if (seenIds.contains(id)) {
                            continue;
                        }

                        String title = rm.optString("title", "Trực tiếp");
                        boolean matchCovered = false;
                        for (String seen : seenTitles) {
                            if (seen.length() > 3 && title.toLowerCase(Locale.ROOT).contains(seen)) {
                                matchCovered = true;
                                break;
                            }
                        }
                        if (matchCovered) {
                            continue;
                        }

                        seenIds.add(id);
                        JSONObject anchor = rm.optJSONObject("anchor");
                        String uid = anchor != null ? String.valueOf(anchor.optLong("uid", 0)) : roomNum;
                        String nick = anchor != null ? anchor.optString("nickName", "BLV") : "BLV";
                        String icon = anchor != null ? anchor.optString("icon", null) : null;
                        String cover = rm.optString("customCoverUrl", rm.optString("cover", icon));

                        boolean isLive = livingStreams.containsKey(uid) || livingStreams.containsKey(roomNum);
                        int liveType = rm.optInt("liveType", rm.optInt("liveTypeParent", 0));
                        String sportKey = liveType == 1 ? "football" : (liveType == 2 ? "basketball" : "other");

                        JSONArray aPayload = new JSONArray();
                        JSONObject aOpt = new JSONObject();
                        aOpt.put("uid", uid);
                        aOpt.put("nick", nick);
                        aOpt.put("room", roomNum);
                        aOpt.put("isLive", isLive);
                        aPayload.put(aOpt);

                        JSONObject payload = new JSONObject();
                        payload.put("source", "socolive");
                        payload.put("id", id);
                        payload.put("title", title);
                        payload.put("sportKey", sportKey);
                        payload.put("anchors", aPayload);

                        JSONObject card = new JSONObject();
                        card.put("id", id);
                        card.put("title", title);
                        card.put("subtitle", "Phòng live " + nick);
                        card.put("pageUrl", "socolive://match?data=" + encode(payload.toString()));
                        card.put("sportKey", sportKey);
                        card.put("homeTeam", title);
                        card.put("awayTeam", nick);
                        card.put("homeLogo", cover);
                        card.put("awayLogo", icon);
                        card.put("image", cover);
                        card.put("leagueLabel", "Socolive Live");
                        card.put("timeLabel", "Đang live");
                        card.put("statusLabel", isLive ? "Trực tiếp" : "Live");

                        itemArray.put(card);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        // 3. Fetch fixtures from s8tv011
        try {
            String fixturesRaw = getAbsolute("https://s8tv011.com/api/fixtures/base", DEFAULT_REFERER);
            JSONObject fixObj = new JSONObject(fixturesRaw);
            JSONArray fixtures = fixObj.optJSONArray("data");
            if (fixtures != null) {
                for (int f = 0; f < fixtures.length(); f++) {
                    JSONObject fix = fixtures.getJSONObject(f);
                    String fixId = "s8_" + fix.optString("id", "");
                    if (seenIds.contains(fixId)) {
                        continue;
                    }

                    JSONObject leftClub = fix.optJSONObject("left_club");
                    JSONObject rightClub = fix.optJSONObject("right_club");
                    String left = leftClub != null ? leftClub.optString("name", "").trim() : "";
                    String right = rightClub != null ? rightClub.optString("name", "").trim() : "";
                    if (left.length() == 0 || right.length() == 0) {
                        continue;
                    }

                    boolean alreadyCovered = false;
                    for (String seen : seenTitles) {
                        if (seen.length() > 3 && left.toLowerCase(Locale.ROOT).contains(seen)) {
                            alreadyCovered = true;
                            break;
                        }
                    }
                    if (alreadyCovered) {
                        continue;
                    }

                    seenIds.add(fixId);
                    String matchTitle = left + " - " + right;
                    JSONObject comp = fix.optJSONObject("competition");
                    String compName = comp != null ? comp.optString("name", "Bóng đá").trim() : "Bóng đá";

                    String timeStr = fix.optString("time", "");
                    String timeLabel = "";
                    if (timeStr.length() >= 16) {
                        timeLabel = timeStr.substring(11, 16);
                        if (timeStr.length() >= 10) {
                            String[] parts = timeStr.substring(0, 10).split("-");
                            if (parts.length == 3) {
                                timeLabel += " " + parts[2] + "/" + parts[1];
                            }
                        }
                    }

                    JSONArray obsArray = fix.optJSONArray("OBS_stream_key");
                    JSONArray aPayload = new JSONArray();
                    if (obsArray != null) {
                        for (int o = 0; o < obsArray.length(); o++) {
                            JSONObject obsItem = obsArray.getJSONObject(o);
                            String streamKey = obsItem.optString("stream_key", "");
                            JSONObject comm = obsItem.optJSONObject("commentator");
                            String commName = comm != null ? comm.optString("name", "BLV") : "BLV";

                            JSONObject aOpt = new JSONObject();
                            aOpt.put("uid", streamKey);
                            aOpt.put("nick", commName);
                            aOpt.put("room", streamKey);
                            aOpt.put("isLive", false);
                            aPayload.put(aOpt);
                        }
                    }

                    JSONObject payload = new JSONObject();
                    payload.put("source", "socolive");
                    payload.put("id", fixId);
                    payload.put("title", matchTitle);
                    payload.put("sportKey", "football");
                    payload.put("anchors", aPayload);

                    JSONObject card = new JSONObject();
                    card.put("id", fixId);
                    card.put("title", matchTitle);
                    card.put("subtitle", compName);
                    card.put("pageUrl", "socolive://match?data=" + encode(payload.toString()));
                    card.put("sportKey", "football");
                    card.put("homeTeam", left);
                    card.put("awayTeam", right);
                    card.put("leagueLabel", compName);
                    card.put("timeLabel", timeLabel);
                    card.put("statusLabel", "Sắp diễn ra");

                    itemArray.put(card);
                }
            }
        } catch (Exception ignored) {
        }

        // Count category statistics
        int totalCount = itemArray.length();
        int liveCount = 0;
        int footballCount = 0;
        int basketballCount = 0;
        int otherCount = 0;

        for (int i = 0; i < itemArray.length(); i++) {
            JSONObject item = itemArray.optJSONObject(i);
            if (item == null) continue;
            String status = item.optString("statusLabel", "");
            String sport = item.optString("sportKey", "");
            if (status.contains("Trực tiếp") || status.contains("Live")) {
                liveCount++;
            }
            if ("football".equals(sport)) {
                footballCount++;
            } else if ("basketball".equals(sport)) {
                basketballCount++;
            } else {
                otherCount++;
            }
        }

        JSONArray categories = new JSONArray();
        try {
            JSONObject catAll = new JSONObject();
            catAll.put("key", "tat-ca");
            catAll.put("label", "Tất cả");
            catAll.put("itemCount", totalCount);
            categories.put(catAll);

            JSONObject catLive = new JSONObject();
            catLive.put("key", "truc-tiep");
            catLive.put("label", "Trực tiếp");
            catLive.put("itemCount", liveCount);
            categories.put(catLive);

            JSONObject catFb = new JSONObject();
            catFb.put("key", "football");
            catFb.put("label", "Bóng đá");
            catFb.put("itemCount", footballCount);
            categories.put(catFb);

            JSONObject catBb = new JSONObject();
            catBb.put("key", "basketball");
            catBb.put("label", "Bóng rổ");
            catBb.put("itemCount", basketballCount);
            categories.put(catBb);

            JSONObject catOther = new JSONObject();
            catOther.put("key", "other");
            catOther.put("label", "Khác");
            catOther.put("itemCount", otherCount);
            categories.put(catOther);

            JSONObject root = new JSONObject();
            root.put("sourceName", "Socolive Trực Tiếp");
            root.put("categories", categories);
            root.put("items", itemArray);

            return SourceListResult.fromJson(root.toString());
        } catch (Exception e) {
            return new SourceListResult("Socolive Trực Tiếp", new ArrayList<>(), new ArrayList<>(), "{}");
        }
    }

    private StreamResult resolveSocoliveStream(String pageUrl, int requestedIndex) {
        String title = "Trực tiếp";
        List<StreamOption> options = new ArrayList<>();
        String chosenPlaybackUrl = null;
        int selectedIndex = Math.max(0, requestedIndex);

        try {
            JSONObject payload = null;
            if (pageUrl.contains("data=")) {
                String encoded = pageUrl.substring(pageUrl.indexOf("data=") + 5);
                int amp = encoded.indexOf('&');
                if (amp >= 0) {
                    encoded = encoded.substring(0, amp);
                }
                String decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
                payload = new JSONObject(decoded);
            }

            if (payload != null) {
                title = payload.optString("title", title);
                JSONArray anchors = payload.optJSONArray("anchors");
                Map<String, String> livingStreams = fetchLivingStreams();

                if (anchors != null && anchors.length() > 0) {
                    String activeAnyStream = null;

                    for (int i = 0; i < anchors.length(); i++) {
                        JSONObject anchor = anchors.getJSONObject(i);
                        String uid = anchor.optString("uid");
                        String room = anchor.optString("room", uid);
                        String nick = anchor.optString("nick", "BLV " + (i + 1));

                        String streamUrl = livingStreams.get(uid);
                        if (streamUrl == null) {
                            streamUrl = livingStreams.get(room);
                        }

                        boolean isLive = streamUrl != null;
                        if (isLive && activeAnyStream == null) {
                            activeAnyStream = streamUrl;
                        }

                        options.add(new StreamOption(i, "BLV " + nick + (isLive ? " (Live)" : "")));

                        if (i == selectedIndex && isLive) {
                            chosenPlaybackUrl = streamUrl;
                        }
                    }

                    // If chosen anchor is not live, use any live commentator for this match
                    if (chosenPlaybackUrl == null && activeAnyStream != null) {
                        chosenPlaybackUrl = activeAnyStream;
                    }

                    // Fallback to room template if match has not started
                    if (chosenPlaybackUrl == null && options.size() > 0) {
                        int clamp = Math.min(selectedIndex, anchors.length() - 1);
                        JSONObject targetAnchor = anchors.getJSONObject(clamp);
                        String targetRoom = targetAnchor.optString("room", targetAnchor.optString("uid"));
                        chosenPlaybackUrl = "https://pull.niues.live/live/stream-" + targetRoom + "_lhd.m3u8";
                    }
                }
            }
        } catch (Exception ignored) {
        }

        if (options.isEmpty()) {
            options.add(new StreamOption(0, "Trực tiếp"));
        }

        if (chosenPlaybackUrl == null) {
            chosenPlaybackUrl = "https://live.inplyr.com/room/default.m3u8";
        }

        try {
            JSONObject result = new JSONObject();
            result.put("title", title);
            result.put("playbackUrl", chosenPlaybackUrl);
            result.put("streamUrl", chosenPlaybackUrl);
            result.put("type", "hls");
            result.put("selectedStreamIndex", selectedIndex);
            JSONArray opts = new JSONArray();
            for (StreamOption opt : options) {
                opts.put(opt.toJson());
            }
            result.put("options", opts);
            return StreamResult.fromJson(result.toString());
        } catch (Exception e) {
            try {
                return StreamResult.fromJson("{\"title\":\"" + title + "\",\"streamUrl\":\"" + chosenPlaybackUrl + "\",\"options\":[{\"index\":0,\"label\":\"Live\"}]}");
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private Map<String, String> fetchLivingStreams() {
        Map<String, String> map = new HashMap<>();
        try {
            String jsonStr = getAbsolute("https://biz.vnres.co/api/live/livingRoom", DEFAULT_REFERER);
            if (jsonStr != null && jsonStr.startsWith("[")) {
                JSONArray array = new JSONArray(jsonStr);
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.getJSONObject(i);
                    String id = item.optString("id");
                    String rawStream = item.optString("stream");
                    if (rawStream.length() > 0) {
                        String m3u8 = rawStream.replaceAll("^https://pull\\d+\\.scstream\\.net/", "https://pull.niues.live/");
                        m3u8 = m3u8.replaceAll("\\.flv(?=([?#]|$))", ".m3u8");
                        if (id.length() > 0) {
                            map.put(id, m3u8);
                        }
                        Matcher m = STREAM_ROOM_PATTERN.matcher(rawStream);
                        if (m.find()) {
                            map.put(m.group(1), m3u8);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return map;
    }

    private synchronized String formatEpoch(long epochMs) {
        return timeFormat.format(new Date(epochMs));
    }

    private static String stripJsonp(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        int firstParen = trimmed.indexOf('(');
        int lastParen = trimmed.lastIndexOf(')');
        if (firstParen > 0 && lastParen > firstParen) {
            return trimmed.substring(firstParen + 1, lastParen).trim();
        }
        return trimmed;
    }

    private String get(String endpoint) throws IOException {
        return getAbsolute(baseUrl + endpoint, null);
    }

    private String getAbsolute(String targetUrl, String referer) throws IOException {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(targetUrl);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("User-Agent", DEFAULT_USER_AGENT);
            if (referer != null) {
                connection.setRequestProperty("Referer", referer);
            }

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
        if (value == null) {
            return "";
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
