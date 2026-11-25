package com.coop.racemgr.gameserver;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compares the settings of the game server's live session (its lobby or race) with what the rotation addon was told
 * to use: the rotation file on the game server (its working copy in lua_config, else the default in lua/sms_rotate),
 * merged the way lib_rotate merges it: the default setup, then the round on top; round Flags are added to the
 * default ones and RemoveFlags taken off; the FORCE_* vehicle flags are the addon's own, so they are not compared.
 *
 * The round is found by the live track (the addon puts a track on every round that names one, or the default's).
 */
public final class LobbyCheck {
    private LobbyCheck() {}

    public record Row(String name, String expected, String live, Boolean match) {}

    public record Result(String state, boolean sessionOpen, Integer round, int rounds, String liveTrack, String note, List<Row> rows) {}

    /** Name to id lists from the game server: tracks, vehicle classes and vehicles. */
    public record Names(Map<String, Long> tracks, Map<String, Long> classes, Map<String, Long> vehicles) {
        static Names empty() {
            return new Names(Map.of(), Map.of(), Map.of());
        }
    }

    private static final List<String> FORCE_FLAGS = List.of("FORCE_IDENTICAL_VEHICLES", "FORCE_SAME_VEHICLE_CLASS", "FORCE_MULTI_VEHICLE_CLASS");
    private static final List<String> CLASS_KEYS = List.of("VehicleClassId", "MultiClassSlot1", "MultiClassSlot2", "MultiClassSlot3", "MultiClassSlot4");

    @SuppressWarnings("unchecked")
    public static Result compare(JsonNode status, Object rotationFile, Integer persistedIndex, SessionSchema.Schema schema, Names names) {
        names = names == null ? Names.empty() : names;
        String state = status == null ? null : text(status.path("state"));
        JsonNode attributes = status == null ? null : status.path("attributes");
        Long liveTrackId = attributes == null ? null : number(attributes.path("TrackId"));
        boolean open = state != null && !"Idle".equalsIgnoreCase(state) && liveTrackId != null && liveTrackId != 0;

        Map<String, Object> config = rotationConfig(rotationFile);
        if (config == null) {
            return new Result(state, open, null, 0, trackName(liveTrackId, names), "The game server's rotation file can't be read.", List.of());
        }
        Map<String, Object> defaults = asMap(config.containsKey("default") ? config.get("default") : config.get("defaultConfig"));
        List<Object> rounds = config.get("rotation") instanceof List<?> list ? (List<Object>) list : List.of();

        // Which round: the one whose track is live; with several, the one the addon's saved position points at
        Integer round = null;
        String note = null;
        if (open) {
            List<Integer> candidates = new ArrayList<>();
            for (int i = 0; i < rounds.size(); i++) {
                Object track = asMap(rounds.get(i)).getOrDefault("TrackId", defaults.get("TrackId"));
                Long id = resolveTrack(track, names);
                if (id != null && id.equals(liveTrackId)) {
                    candidates.add(i + 1);
                }
            }
            if (candidates.size() == 1) {
                round = candidates.get(0);
            } else if (candidates.size() > 1) {
                round = candidates.get(0);
                if (persistedIndex != null) {
                    for (int c : candidates) {
                        if (c == persistedIndex || c == persistedIndex + 1) {
                            round = c;
                            break;
                        }
                    }
                }
            } else if (!rounds.isEmpty()) {
                note = "The live track isn't in the rotation file, so this session doesn't come from the rotation: compared with the default setup only.";
            }
        } else if (persistedIndex != null && !rounds.isEmpty()) {
            // No session: show what the next lobby should get (the addon has moved on to the round after the saved one)
            round = Math.floorMod(persistedIndex, rounds.size()) + 1;
        }

        Map<String, Object> merged = new LinkedHashMap<>(defaults);
        long expectedFlags = flagsOf(defaults.get("Flags"), schema);
        if (round != null) {
            Map<String, Object> setup = asMap(rounds.get(round - 1));
            for (Map.Entry<String, Object> e : setup.entrySet()) {
                if (!"Flags".equals(e.getKey()) && !"RemoveFlags".equals(e.getKey())) {
                    merged.put(e.getKey(), e.getValue());
                }
            }
            expectedFlags |= flagsOf(setup.get("Flags"), schema);
            expectedFlags &= ~flagsOf(setup.get("RemoveFlags"), schema);
        }
        long forceMask = flagsOf(String.join(",", FORCE_FLAGS), schema);

        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, Object> e : merged.entrySet()) {
            String key = e.getKey();
            JsonNode liveNode = open && attributes != null ? attributes.path(key) : null;
            Long live = liveNode == null || liveNode.isMissingNode() ? null : number(liveNode);
            if ("Flags".equals(key)) {
                long want = expectedFlags & ~forceMask;
                Long have = live == null ? null : (live & 0xFFFFFFFFL) & ~forceMask;
                rows.add(new Row(key, flagText(want, schema), have == null ? null : flagText(have, schema),
                        have == null ? null : have == want));
                continue;
            }
            Long want = resolve(key, e.getValue(), schema, names);
            rows.add(new Row(key, display(key, want, e.getValue(), schema, names),
                    live == null ? null : display(key, live, null, schema, names),
                    want == null || live == null ? null : want.equals(live)));
        }
        if (round != null && !merged.containsKey("Flags") && expectedFlags != 0) {
            rows.add(0, new Row("Flags", flagText(expectedFlags & ~forceMask, schema), null, null));
        }
        return new Result(state, open, round, rounds.size(), trackName(liveTrackId, names), note, rows);
    }

    /** The addon's saved rotation position (lua_config/sms_rotate_data.json), or null. */
    public static Integer persistedIndex(Object dataFile) {
        Object index = dataFile instanceof Map<?, ?> map ? map.get("index") : null;
        if (index == null && dataFile instanceof Map<?, ?> map && map.get("data") instanceof Map<?, ?> inner) {
            index = inner.get("index");
        }
        return index instanceof Number n ? n.intValue() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rotationConfig(Object file) {
        if (!(file instanceof Map<?, ?> root)) {
            return null;
        }
        Object config = root.get("config");
        return config instanceof Map<?, ?> c ? (Map<String, Object>) c : (Map<String, Object>) root;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static Long resolve(String key, Object value, SessionSchema.Schema schema, Names names) {
        if ("TrackId".equals(key)) {
            return resolveTrack(value, names);
        }
        if (CLASS_KEYS.contains(key)) {
            return lookup(value, names.classes());
        }
        if ("VehicleModelId".equals(key)) {
            return lookup(value, names.vehicles());
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        if (value instanceof String s) {
            Long parsed = parse(s);
            if (parsed != null) {
                return parsed;
            }
            SessionSchema.Option option = option(key, s, schema);
            return option == null ? null : option.value();
        }
        return null;
    }

    private static Long resolveTrack(Object value, Names names) {
        return lookup(value, names.tracks());
    }

    private static Long lookup(Object value, Map<String, Long> byName) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            Long parsed = parse(s);
            if (parsed != null) {
                return parsed;
            }
            for (Map.Entry<String, Long> e : byName.entrySet()) {
                if (e.getKey().equalsIgnoreCase(s.trim())) {
                    return e.getValue();
                }
            }
        }
        return null;
    }

    private static SessionSchema.Option option(String key, String name, SessionSchema.Schema schema) {
        List<SessionSchema.Option> options = optionsFor(key, schema);
        if (options == null) {
            return null;
        }
        for (SessionSchema.Option o : options) {
            if (o.name().equalsIgnoreCase(name.trim())) {
                return o;
            }
        }
        return null;
    }

    private static List<SessionSchema.Option> optionsFor(String key, SessionSchema.Schema schema) {
        if (schema == null) {
            return null;
        }
        for (SessionSchema.Attribute a : schema.attributes()) {
            if (a.name().equals(key) && "enum".equals(a.kind()) && a.enumName() != null) {
                return schema.enums().get(a.enumName());
            }
        }
        return null;
    }

    private static String display(String key, Long value, Object original, SessionSchema.Schema schema, Names names) {
        if (value == null) {
            return original == null ? null : String.valueOf(original) + " (not known to the game server)";
        }
        if ("TrackId".equals(key)) {
            return nameOf(value, names.tracks());
        }
        if (CLASS_KEYS.contains(key)) {
            return nameOf(value, names.classes());
        }
        if ("VehicleModelId".equals(key)) {
            return nameOf(value, names.vehicles());
        }
        List<SessionSchema.Option> options = optionsFor(key, schema);
        if (options != null) {
            for (SessionSchema.Option o : options) {
                if (o.value() == value) {
                    return o.name();
                }
            }
        }
        return String.valueOf(value);
    }

    private static String nameOf(long id, Map<String, Long> byName) {
        for (Map.Entry<String, Long> e : byName.entrySet()) {
            if (e.getValue() == id) {
                return e.getKey();
            }
        }
        return String.valueOf(id);
    }

    private static String trackName(Long id, Names names) {
        return id == null || id == 0 ? null : nameOf(id, names.tracks());
    }

    private static long flagsOf(Object value, SessionSchema.Schema schema) {
        if (value instanceof Number n) {
            return n.longValue() & 0xFFFFFFFFL;
        }
        if (!(value instanceof String s) || s.isBlank()) {
            return 0;
        }
        Long parsed = parse(s);
        if (parsed != null) {
            return parsed & 0xFFFFFFFFL;
        }
        long bits = 0;
        if (schema != null) {
            for (String part : s.split(",")) {
                for (SessionSchema.Option f : schema.flags()) {
                    if (f.name().equalsIgnoreCase(part.trim())) {
                        bits |= f.value() & 0xFFFFFFFFL;
                    }
                }
            }
        }
        return bits;
    }

    private static String flagText(long bits, SessionSchema.Schema schema) {
        if (schema == null) {
            return String.valueOf(bits);
        }
        List<String> on = new ArrayList<>();
        for (SessionSchema.Option f : schema.flags()) {
            long bit = f.value() & 0xFFFFFFFFL;
            if (bit != 0 && (bits & bit) == bit) {
                on.add(f.name());
            }
        }
        return on.isEmpty() ? "(none)" : String.join(",", on);
    }

    private static Long number(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.longValue();
        }
        return node.isString() ? parse(node.asString()) : null;
    }

    private static Long parse(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
    }
}
