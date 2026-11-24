package com.coop.racemgr.gameserver;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The session attributes that may be set for the next session: the writable session settings the game server
 * describes (the same list rotations may use, without the ServerControls* switches), whole numbers only. The
 * common ones also have sane bounds. Anything else is refused, so a request can't set arbitrary attributes.
 */
public final class SessionSettings {
    private SessionSettings() {}

    private record Range(int min, int max) {}

    private static final int MAX_SETTINGS = 100;

    // Bounds for the common ones; the rest only need to be whole numbers
    private static final Map<String, Range> ALLOWED = new LinkedHashMap<>();

    static {
        // Ids are signed 32-bit values (tracks and vehicles can have negative ids)
        ALLOWED.put("TrackId", new Range(Integer.MIN_VALUE, Integer.MAX_VALUE));
        ALLOWED.put("VehicleClassId", new Range(Integer.MIN_VALUE, Integer.MAX_VALUE));
        ALLOWED.put("VehicleModelId", new Range(Integer.MIN_VALUE, Integer.MAX_VALUE));
        ALLOWED.put("PracticeLength", new Range(0, 600));
        ALLOWED.put("QualifyLength", new Range(0, 600));
        ALLOWED.put("RaceLength", new Range(1, 999));
        ALLOWED.put("OpponentDifficulty", new Range(0, 100));
        ALLOWED.put("MaxPlayers", new Range(1, 64));
        ALLOWED.put("GridSize", new Range(1, 64));
        ALLOWED.put("DamageType", new Range(0, 10));
        ALLOWED.put("TireWearType", new Range(0, 10));
        ALLOWED.put("FuelUsageType", new Range(0, 10));
        ALLOWED.put("PenaltiesType", new Range(0, 10));
        ALLOWED.put("RaceDateHour", new Range(0, 23));
    }

    /** @return a human readable problem, or null when every setting is allowed and in range */
    public static String validate(Map<String, Integer> settings) {
        if (settings == null || settings.isEmpty()) {
            return "No settings given";
        }
        if (settings.size() > MAX_SETTINGS) {
            return "Too many settings";
        }
        for (var entry : settings.entrySet()) {
            String key = entry.getKey();
            Range range = ALLOWED.get(key);
            if (range == null && !com.coop.racemgr.rotation.RotationValidator.allowedSettings().contains(key)) {
                return "Setting not allowed: " + key.replaceAll("[^A-Za-z0-9_]", "?");
            }
            Integer value = entry.getValue();
            // Values are 32-bit; ids, weather and flag values are often negative
            if (value == null || (range != null && (value < range.min() || value > range.max()))) {
                return key + " is out of range";
            }
        }
        return null;
    }

    // The game server names these session_<Attribute>
    static URI setNextUri(String baseUrl, Map<String, Integer> settings) {
        var builder = UriComponentsBuilder.fromUriString(baseUrl + "/session/set_next_attributes");
        Map<String, Object> variables = new LinkedHashMap<>();
        int i = 0;
        for (var entry : settings.entrySet()) {
            String variable = "v" + i++;
            builder.queryParam("session_" + entry.getKey(), "{" + variable + "}");
            variables.put(variable, entry.getValue());
        }
        return builder.encode().buildAndExpand(variables).toUri();
    }

    static URI advanceUri(String baseUrl) {
        return URI.create(baseUrl + "/session/advance");
    }
}
