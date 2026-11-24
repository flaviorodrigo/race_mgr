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

    // The session attributes a dedicated server lists as writable (its /api/list/attributes/session), except the
    // ServerControls* switches, which decide who controls the setup and are left to the rotation addon itself.
    // The rotation addon applies these to each session, so a real rotation file may use any of them.
    private static final Set<String> SESSION_ATTRIBUTES = Set.of(
            "GridSize", "GridLayout", "MaxPlayers", "OpponentDifficulty", "Flags", "AutoAdvanceSession",
            "DamageType", "DamageScale", "DamageRandomFailures", "TireWearType", "FuelUsageType", "PenaltiesType",
            "PitWhiteLinePenalty", "DriveThroughPenalty", "AllowedCutsBeforePenalty", "PitSpeedLimit", "ManualPitStops",
            "ManualRollingStarts", "AllowedViews", "FullCourseYellows", "TrackId", "VehicleClassId", "MultiClassSlots",
            "MultiClassSlot1", "MultiClassSlot2", "MultiClassSlot3", "MultiClassSlot4", "MultiClassSlot5", "MultiClassSlot6",
            "MultiClassSlot7", "MultiClassSlot8", "MultiClassSlot9", "VehicleModelId", "MinimumOnlineRank", "MinimumOnlineStrength",
            "PracticeLength", "PracticeDateHour", "PracticeDateProgression", "PracticeWeatherProgression", "PracticeWeatherSlots",
            "PracticeWeatherSlot1", "PracticeWeatherSlot2", "PracticeWeatherSlot3", "PracticeWeatherSlot4", "PracticeLiveTrackPreset",
            "QualifyLength", "QualifyDateHour", "QualifyDateProgression", "QualifyWeatherProgression", "QualifyWeatherSlots",
            "QualifyWeatherSlot1", "QualifyWeatherSlot2", "QualifyWeatherSlot3", "QualifyWeatherSlot4", "QualifyLiveTrackPreset",
            "QualifyPrivateSession", "QualifyStintLimit", "QualifyLapLimit",
            "RaceLength", "RaceExtraLap", "RaceDateYear", "RaceDateMonth", "RaceDateDay", "RaceDateHour", "RaceDateProgression",
            "RaceWeatherProgression", "RaceWeatherSlots", "RaceWeatherSlot1", "RaceWeatherSlot2", "RaceWeatherSlot3", "RaceWeatherSlot4",
            "RaceRollingStart", "RaceMandatoryPitStops", "RaceMandatoryPitStopsMinTyres", "RaceFormationLap", "RaceLiveTrackPreset",
            "RaceScheduledFullCourseYellow", "DisablePitstopRefuelling");

    /** The settings a rotation may use. */
    public static Set<String> allowedSettings() {
        return SESSION_ATTRIBUTES;
    }

    private static final Set<String> DEFAULT_KEYS = SESSION_ATTRIBUTES;
    // A rotation entry may change any of them for its round (the addon applies it on top of the default setup)
    private static final Set<String> ENTRY_KEYS = SESSION_ATTRIBUTES;

    // Counts and lengths are small and never negative; everything else is a 32-bit value (ids, weather and flag
    // hashes are often negative)
    private static boolean isCount(String key) {
        return key.endsWith("Length") || key.endsWith("Slots") || key.equals("GridSize") || key.equals("MaxPlayers")
                || key.equals("OpponentDifficulty");
    }

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
        if (requireTrack && !(map.get("TrackId") instanceof String || map.get("TrackId") instanceof Integer)) {
            return "The " + where + " needs a track";
        }
        for (var entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!allowed.contains(key)) {
                return "Unknown setting in the " + where + ": " + printable(key);
            }
            Object value = entry.getValue();
            boolean ok = value instanceof Integer n ? !isCount(key) || (n >= 0 && n <= 1000)
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
