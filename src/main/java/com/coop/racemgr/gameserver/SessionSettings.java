package com.coop.racemgr.gameserver;

import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The session attributes admins may change for the next session from the UI. Only these names are accepted, each
 * with sane bounds, so a request can't set arbitrary game server attributes.
 */
public final class SessionSettings {
    private SessionSettings() {}

    private record Range(int min, int max) {}

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
        for (var entry : settings.entrySet()) {
            Range range = ALLOWED.get(entry.getKey());
            if (range == null) {
                return "Setting not allowed: " + entry.getKey().replaceAll("[^A-Za-z0-9_]", "?");
            }
            Integer value = entry.getValue();
            if (value == null || value < range.min() || value > range.max()) {
                return entry.getKey() + " is out of range";
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
