package com.anhviet.avsport.model;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MediaCard {
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})(?:\\s+(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{4}))?)?");
    public final String id;
    public final String title;
    public final String subtitle;
    public final String badge;
    public final String image;
    public final String pageUrl;
    public final String sportKey;
    public final String homeTeam;
    public final String awayTeam;
    public final String homeLogo;
    public final String awayLogo;
    public final String competitionLogo;
    public final String leagueLabel;
    public final String timeLabel;
    public final String statusLabel;
    public final String homeScore;
    public final String awayScore;

    public MediaCard(JSONObject json) {
        id = json.optString("id");
        title = json.optString("title");
        subtitle = emptyToNull(json.optString("subtitle", null));
        badge = emptyToNull(json.optString("badge", null));
        image = emptyToNull(json.optString("image", null));
        pageUrl = json.optString("pageUrl");
        sportKey = emptyToNull(json.optString("sportKey", null));
        homeTeam = emptyToNull(json.optString("homeTeam", null));
        awayTeam = emptyToNull(json.optString("awayTeam", null));
        homeLogo = emptyToNull(json.optString("homeLogo", null));
        awayLogo = emptyToNull(json.optString("awayLogo", null));
        competitionLogo = emptyToNull(json.optString("competitionLogo", null));
        leagueLabel = emptyToNull(json.optString("leagueLabel", null));
        timeLabel = emptyToNull(json.optString("timeLabel", null));
        statusLabel = emptyToNull(json.optString("statusLabel", null));
        homeScore = emptyToNull(json.optString("homeScore", null));
        awayScore = emptyToNull(json.optString("awayScore", null));
    }

    public String displayTitle() {
        if (homeTeam != null && awayTeam != null) {
            return homeTeam + " - " + awayTeam;
        }

        return title == null || title.length() == 0 ? "Trận đấu" : title;
    }

    public String displayTime() {
        return timeLabel == null || timeLabel.length() == 0 ? "--:--" : timeLabel;
    }

    public String displayStatus() {
        if (statusLabel == null || statusLabel.length() == 0) {
            return inferStatusFromTime();
        }

        return normalizeStatus(statusLabel);
    }

    public boolean isEnded() {
        return statusLabel != null && normalizeStatus(statusLabel).toLowerCase().contains("kết");
    }

    public String displayLeague() {
        if (leagueLabel != null && leagueLabel.length() > 0) {
            return leagueLabel;
        }

        if (subtitle != null && subtitle.length() > 0) {
            return subtitle;
        }

        return "Trực tiếp";
    }

    public String displayScore() {
        if (homeScore != null && awayScore != null) {
            return homeScore + " : " + awayScore;
        }

        return null;
    }

    private static String emptyToNull(String value) {
        return value == null || value.length() == 0 || "null".equals(value) ? null : value;
    }

    private String inferStatusFromTime() {
        Long startTime = parseStartTimeMillis();
        if (startTime == null) {
            return "Chưa diễn ra";
        }

        long now = System.currentTimeMillis();
        if (startTime > now) {
            return "Chưa diễn ra";
        }

        return "Live";
    }

    private Long parseStartTimeMillis() {
        if (timeLabel == null || timeLabel.length() == 0) {
            return null;
        }

        Matcher matcher = TIME_PATTERN.matcher(timeLabel);
        if (!matcher.find()) {
            return null;
        }

        try {
            Calendar now = Calendar.getInstance();
            Calendar start = Calendar.getInstance();
            start.set(Calendar.HOUR_OF_DAY, Integer.parseInt(matcher.group(1)));
            start.set(Calendar.MINUTE, Integer.parseInt(matcher.group(2)));
            start.set(Calendar.SECOND, 0);
            start.set(Calendar.MILLISECOND, 0);

            if (matcher.group(3) != null && matcher.group(4) != null) {
                start.set(Calendar.DAY_OF_MONTH, Integer.parseInt(matcher.group(3)));
                start.set(Calendar.MONTH, Integer.parseInt(matcher.group(4)) - 1);
                if (matcher.group(5) != null) {
                    start.set(Calendar.YEAR, Integer.parseInt(matcher.group(5)));
                } else {
                    start.set(Calendar.YEAR, now.get(Calendar.YEAR));
                }
            }

            return start.getTimeInMillis();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String normalizeStatus(String value) {
        String lower = value.toLowerCase();
        if (lower.contains("kết") || lower.contains("finished") || lower.contains("ended")) {
            return "Kết thúc";
        }
        if (lower.contains("live") || lower.contains("trực") || lower.contains("đang")) {
            return "Live";
        }
        return "Chưa diễn ra";
    }
}
