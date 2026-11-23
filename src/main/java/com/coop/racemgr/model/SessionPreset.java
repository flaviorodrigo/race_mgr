package com.coop.racemgr.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Map;

/** A named set of next-session settings (track, laps, qualifying minutes...) an admin can apply with one click. */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document("session_presets")
public class SessionPreset {
    public static final int MAX_PRESETS = 50;
    public static final int MAX_NAME_LENGTH = 40;

    @Id
    private String id;
    private String name;
    // The same names and values the "next session" form sends (checked by SessionSettings)
    private Map<String, Integer> settings;

    /** @return the cleaned name, or null when it is empty, too long or has control characters */
    public static String cleanName(String name) {
        if (name == null) {
            return null;
        }
        String trimmed = name.trim();
        return trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH || trimmed.matches(".*\\p{Cntrl}.*") ? null : trimmed;
    }
}
