package com.coop.racemgr.rotation;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a custom rotation before it is written to the game server's sms_rotate config file: only the known
 * settings, simple values, a bounded number of entries. The game server's Lua reads this file, so a malformed or
 * oversized one could stop rotations (or the server) working.
 */
public final class RotationValidator {
    private RotationValidator() {}

    public static final int MAX_ENTRIES = 200;
    private static final Pattern SAFE_TEXT = Pattern.compile("^[A-Za-z0-9_,.()\\- ]{1,200}$");

    private static final Set<String> DEFAULT_KEYS = Set.of(
            "PracticeLength", "QualifyLength", "RaceLength", "VehicleClassId", "Flags", "DamageType", "TireWearType",
            "FuelUsageType", "PenaltiesType", "AllowedViews", "QualifyDateHour", "QualifyDateProgression",
            "QualifyWeatherSlots", "QualifyWeatherSlot1", "QualifyWeatherSlot2", "QualifyWeatherSlot3", "QualifyWeatherSlot4",
            "RaceWeatherSlots", "RaceWeatherSlot1", "RaceWeatherSlot2", "RaceWeatherSlot3", "RaceWeatherSlot4",
            "RaceDateProgression");

    private static final Set<String> ENTRY_KEYS = Set.of(
            "TrackId", "RaceLength", "QualifyLength", "PracticeLength",
            "QualifyWeatherSlot1", "QualifyWeatherSlot2", "QualifyWeatherSlot3", "QualifyWeatherSlot4",
            "RaceWeatherSlot1", "RaceWeatherSlot2", "RaceWeatherSlot3", "RaceWeatherSlot4");

    /** @return a problem description, or null when the rotation is acceptable */
    public static String validate(Object config) {
        if (!(config instanceof Map<?, ?> root)) {
            return "The rotation needs a configuration object";
        }
        for (Object key : root.keySet()) {
            if (!Set.of("persist_index", "defaultConfig", "default", "rotation").contains(String.valueOf(key))) {
                return "Unknown rotation setting: " + printable(key);
            }
        }

        Object defaults = root.containsKey("defaultConfig") ? root.get("defaultConfig") : root.get("default");
        String problem = validateValues(defaults, DEFAULT_KEYS, "default settings", false);
        if (problem != null) {
            return problem;
        }
        if (root.containsKey("persist_index") && !(root.get("persist_index") instanceof Boolean)) {
            return "persist_index must be true or false";
        }

        if (!(root.get("rotation") instanceof List<?> entries) || entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            return "The rotation needs 1 to " + MAX_ENTRIES + " entries";
        }
        for (int i = 0; i < entries.size(); i++) {
            problem = validateValues(entries.get(i), ENTRY_KEYS, "entry " + (i + 1), true);
            if (problem != null) {
                return problem;
            }
        }
        return null;
    }

    private static String validateValues(Object node, Set<String> allowed, String where, boolean requireTrack) {
        if (!(node instanceof Map<?, ?> map)) {
            return "The " + where + " must be an object";
        }
        if (requireTrack && !(map.get("TrackId") instanceof String)) {
            return "The " + where + " needs a track";
        }
        for (var entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!allowed.contains(key)) {
                return "Unknown setting in the " + where + ": " + printable(key);
            }
            Object value = entry.getValue();
            boolean ok = value instanceof Integer n ? n >= 0 && n <= 100_000
                    : value instanceof String s && SAFE_TEXT.matcher(s).matches();
            if (!ok) {
                return "Invalid value for " + key + " in the " + where;
            }
        }
        return null;
    }

    private static String printable(Object value) {
        return String.valueOf(value).replaceAll("[^A-Za-z0-9_.]", "?");
    }
}
