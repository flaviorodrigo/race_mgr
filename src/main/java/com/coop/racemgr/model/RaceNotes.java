package com.coop.racemgr.model;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What an admin can add to a finished race afterwards: a video link and penalties. Everything entered is checked
 * and cleaned here, because both are shown to every visitor.
 */
public final class RaceNotes {
    public static final int MAX_PENALTIES = 30;
    public static final int MAX_PENALTY_SECONDS = 3600;
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_NOTE_LENGTH = 120;
    public static final int MAX_URL_LENGTH = 200;

    // Only well-known video sites: the link is shown to every visitor
    private static final Set<String> VIDEO_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "twitch.tv", "www.twitch.tv");

    private RaceNotes() {}

    /** A penalty, or a disqualification, for one driver of the race. */
    public record Penalty(String driver, Integer seconds, Boolean disqualify, String note) {}

    /** @return the cleaned link, "" to clear it, or null when it isn't an https YouTube or Twitch link */
    public static String cleanVideoUrl(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String url = value.trim();
        if (url.length() > MAX_URL_LENGTH || url.chars().anyMatch(c -> c <= 32 || c == '"' || c == '<' || c == '>' || c == '\\')) {
            return null;
        }
        try {
            URI uri = new URI(url);
            boolean ok = "https".equals(uri.getScheme()) && uri.getUserInfo() == null && uri.getPort() == -1
                    && uri.getHost() != null && VIDEO_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT));
            return ok ? url : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @param raceDrivers the drivers that took part: a penalty can only name one of them
     * @return the cleaned penalties, or null when something is invalid
     */
    public static List<Penalty> cleanPenalties(List<Penalty> penalties, Set<String> raceDrivers) {
        if (penalties == null || penalties.size() > MAX_PENALTIES) {
            return null;
        }
        List<Penalty> cleaned = new ArrayList<>();
        for (Penalty p : penalties) {
            if (p == null || p.driver() == null || !raceDrivers.contains(p.driver()) || p.driver().length() > MAX_NAME_LENGTH) {
                return null;
            }
            boolean disqualify = Boolean.TRUE.equals(p.disqualify());
            int seconds = disqualify || p.seconds() == null ? 0 : p.seconds();
            if (seconds < 0 || seconds > MAX_PENALTY_SECONDS || (!disqualify && seconds == 0)) {
                return null;
            }
            String note = p.note() == null ? "" : p.note().replaceAll("\\p{Cntrl}", " ").trim();
            if (note.length() > MAX_NOTE_LENGTH) {
                return null;
            }
            cleaned.add(new Penalty(p.driver(), seconds, disqualify, note));
        }
        return cleaned;
    }
}
