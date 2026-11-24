package com.coop.racemgr.gameserver;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything a session (and so a rotation) can set, described by the game server itself: its writable session
 * attributes with their descriptions, the option lists (enums) and the session flags. The rotation builder draws its
 * form from this, so new game settings appear without changes to Race Manager.
 */
public final class SessionSchema {
    private SessionSchema() {}

    /**
     * @param kind  how to edit it: enum (pick one option), flags (tick several), track, vehicleClass, vehicle, toggle (0/1) or number
     * @param enumName the option list for kind=enum
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Attribute(String name, String type, String description, String kind, String enumName) {}

    public record Option(String name, long value) {}

    public record Schema(List<Attribute> attributes, Map<String, List<Option>> enums, List<Option> flags) {}

    private static final Pattern ENUM_LINK = Pattern.compile("/api/list/enums/([a-z_]+)");
    private static final Pattern TOGGLE = Pattern.compile("(?i)set (to )?1 to enable|set to 0/1|\\(0\\) disabled \\(1\\) enabled|0/1 to disable/enable");

    /** The enums the attribute list points at that /api/list/enums doesn't include (the game server lists them apart). */
    public static final List<String> SEPARATE_ENUMS = List.of("privatesession", "scheduledfcy");

    public static Schema build(JsonNode attributes, JsonNode enums, Map<String, JsonNode> separateEnums, JsonNode flags) {
        Map<String, List<Option>> options = new LinkedHashMap<>();
        if (enums.isObject()) {
            for (var e : enums.properties()) {
                options.put(e.getKey(), options(e.getValue().path("list")));
            }
        }
        separateEnums.forEach((name, node) -> options.put(name, options(node.path("list"))));

        List<Attribute> result = new ArrayList<>();
        for (JsonNode a : attributes.path("list")) {
            String name = a.path("name").asString("");
            String access = a.path("access").asString("");
            // Read-only values describe the running session; ServerControls* are left to the rotation addon
            if (name.isEmpty() || access.equals("ReadOnly") || name.startsWith("ServerControls")) {
                continue;
            }
            String description = a.path("description").asString("");
            String enumName = enumFor(name, description, options);
            result.add(new Attribute(name, a.path("type").asString(""), description, kindOf(name, description, enumName), enumName));
        }
        return new Schema(result, options, options(flags.path("list")));
    }

    private static String enumFor(String name, String description, Map<String, List<Option>> options) {
        // The game's own descriptions point the Live Track presets at the wrong list (or none)
        if (name.endsWith("LiveTrackPreset")) {
            return options.containsKey("livetrack_preset") ? "livetrack_preset" : null;
        }
        Matcher m = ENUM_LINK.matcher(description);
        return m.find() && options.containsKey(m.group(1)) ? m.group(1) : null;
    }

    private static String kindOf(String name, String description, String enumName) {
        if (name.equals("Flags")) {
            return "flags";
        }
        if (name.equals("TrackId")) {
            return "track";
        }
        if (name.equals("VehicleClassId") || name.matches("MultiClassSlot\\d")) {
            return "vehicleClass";
        }
        if (name.equals("VehicleModelId")) {
            return "vehicle";
        }
        if (enumName != null) {
            return "enum";
        }
        return TOGGLE.matcher(description).find() ? "toggle" : "number";
    }

    private static List<Option> options(JsonNode list) {
        List<Option> result = new ArrayList<>();
        if (list.isArray()) {
            for (JsonNode o : list) {
                if (o.path("name").isString() && o.path("value").isNumber()) {
                    result.add(new Option(o.path("name").asString(), o.path("value").asLong()));
                }
            }
        }
        return result;
    }
}
