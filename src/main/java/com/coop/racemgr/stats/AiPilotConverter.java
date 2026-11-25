package com.coop.racemgr.stats;

import com.coop.racemgr.gameserver.LiveTiming;
import com.coop.racemgr.model.Race;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Translates AMS2 multiplayer generic AI names (e.g. "Jose Lopez (AI)") into real pilots from
 * {@code AgyAMS2RealNames.dat} using the same class + track-aware skill-tier matching as the SimHub plugin,
 * while keeping AI entries clearly marked as AI.
 */
public final class AiPilotConverter {
    private AiPilotConverter() {}

    public interface Lookup {
        String vehicleClassForVehicleId(Integer vehicleId);
        String vehicleClassForClassId(Integer classId);
        String trackNameForTrackId(String trackId);
    }

    public record DriverInput(String name, boolean player, String carClass) {}

    private record RealPilot(String name, String carNo, double score) {}

    private record SkillOverride(Double race, Double quali) {}

    private static final Pattern AI_SUFFIX = Pattern.compile("\\s*\\((AI|KI)\\)$", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Integer> GENERIC_LEVEL_MAP = new HashMap<>();
    private static final Map<String, List<RealPilot>> CLASS_ROSTER_MAP = new HashMap<>();
    private static final Map<String, Double> QUALI_BASE_MAP = new HashMap<>();
    private static final Map<String, SkillOverride> TRACK_SKILL_MAP = new HashMap<>();
    private static final Map<String, List<RealPilot>> SORTED_ROSTER_CACHE = new ConcurrentHashMap<>();

    static {
        loadData();
    }

    private static void loadData() {
        try (InputStream is = AiPilotConverter.class.getResourceAsStream("/AgyAMS2RealNames.dat")) {
            if (is == null) {
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String[] parts = line.split("\t", -1);
                    if (parts.length < 3) {
                        continue;
                    }
                    switch (parts[0]) {
                        case "L" -> {
                            try {
                                GENERIC_LEVEL_MAP.put(lower(parts[1]), Integer.parseInt(parts[2].trim()));
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        case "R" -> {
                            if (parts.length >= 5) {
                                double score = parseDouble(parts[4], 0.85);
                                CLASS_ROSTER_MAP
                                        .computeIfAbsent(lower(parts[1]), k -> new ArrayList<>())
                                        .add(new RealPilot(parts[2], parts[3], score));
                            }
                        }
                        case "Q" -> {
                            if (parts.length >= 4) {
                                Double qs = parseDoubleOrNull(parts[3]);
                                if (qs != null) {
                                    QUALI_BASE_MAP.put(lower(parts[1]) + "|" + lower(parts[2]), qs);
                                }
                            }
                        }
                        case "T" -> {
                            if (parts.length >= 6) {
                                Double rs = parseDoubleOrNull(parts[4]);
                                Double qs = parseDoubleOrNull(parts[5]);
                                if (rs != null || qs != null) {
                                    TRACK_SKILL_MAP.put(
                                            lower(parts[1]) + "|" + lower(parts[2]) + "|" + lower(parts[3]),
                                            new SkillOverride(rs, qs)
                                    );
                                }
                            }
                        }
                        default -> {
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static double parseDouble(String s, double fallback) {
        Double d = parseDoubleOrNull(s);
        return d != null ? d : fallback;
    }

    private static Double parseDoubleOrNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String stripAiSuffix(String name) {
        if (name == null) {
            return "";
        }
        return AI_SUFFIX.matcher(name).replaceAll("").trim();
    }

    public static String normalizeClass(String carClass) {
        if (carClass == null) {
            return "";
        }
        String trimmed = carClass.trim();
        String up = trimmed.toUpperCase(Locale.ROOT);
        if (up.endsWith("_LD") || up.endsWith("_HD") || up.endsWith("_SO") || up.endsWith("_SS") || up.endsWith("_SW")) {
            return trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed;
    }

    private static double effectiveScore(RealPilot p, String normClassLower, String trackLower) {
        double raceScore = p.score();
        double qualiScore = p.score();
        Double qb = QUALI_BASE_MAP.get(normClassLower + "|" + lower(p.name()));
        if (qb != null) {
            qualiScore = qb;
        }
        if (trackLower != null && !trackLower.isEmpty()) {
            SkillOverride ov = TRACK_SKILL_MAP.get(normClassLower + "|" + trackLower + "|" + lower(p.name()));
            if (ov != null) {
                if (ov.race() != null) {
                    raceScore = ov.race();
                }
                if (ov.quali() != null) {
                    qualiScore = ov.quali();
                }
            }
        }
        return raceScore + qualiScore;
    }

    private static List<RealPilot> getEffectiveRoster(String normClass, String trackName) {
        String normClassLower = lower(normClass);
        List<RealPilot> base = CLASS_ROSTER_MAP.get(normClassLower);
        if (base == null || base.size() <= 1) {
            return base;
        }
        String trackLower = lower(trackName);
        String cacheKey = normClassLower + "|" + trackLower;
        return SORTED_ROSTER_CACHE.computeIfAbsent(cacheKey, k -> {
            List<RealPilot> sorted = new ArrayList<>(base);
            sorted.sort((a, b) -> {
                double sa = effectiveScore(a, normClassLower, trackLower);
                double sb = effectiveScore(b, normClassLower, trackLower);
                int cmp = Double.compare(sb, sa);
                if (cmp != 0) {
                    return cmp;
                }
                return Integer.compare(base.indexOf(a), base.indexOf(b));
            });
            return sorted;
        });
    }

    /**
     * Maps generic AI driver names to unique real pilot names for one race/session.
     * Keys in the returned map are {@code lower(normClass) + ":" + lower(rawAiName)}.
     */
    public static Map<String, String> buildSessionMapping(List<DriverInput> drivers, String trackName) {
        Map<String, String> mapping = new LinkedHashMap<>();
        if (drivers == null || drivers.isEmpty()) {
            return mapping;
        }

        Set<String> usedNames = new HashSet<>();
        record AiEntry(String normClass, String rawName, int level) {}
        List<AiEntry> aiEntries = new ArrayList<>();
        Set<String> seenEntries = new HashSet<>();

        for (DriverInput d : drivers) {
            if (d == null || d.name() == null || d.name().isBlank()) {
                continue;
            }
            String rawName = stripAiSuffix(d.name());
            Integer lv = GENERIC_LEVEL_MAP.get(lower(rawName));
            if (d.player() || lv == null) {
                usedNames.add(lower(rawName));
                continue;
            }
            String normClass = normalizeClass(d.carClass());
            if (normClass.isEmpty()) {
                continue;
            }
            String entryKey = lower(normClass) + ":" + lower(rawName);
            if (seenEntries.add(entryKey)) {
                aiEntries.add(new AiEntry(normClass, rawName, lv));
            }
        }

        aiEntries.sort(Comparator.comparingInt(AiEntry::level)
                .thenComparing(e -> lower(e.rawName())));

        for (AiEntry entry : aiEntries) {
            List<RealPilot> roster = getEffectiveRoster(entry.normClass(), trackName);
            if (roster == null) {
                continue;
            }
            for (RealPilot pilot : roster) {
                if (usedNames.add(lower(pilot.name()))) {
                    mapping.put(lower(entry.normClass()) + ":" + lower(entry.rawName()), pilot.name());
                    break;
                }
            }
        }

        return mapping;
    }

    /**
     * Converts generic AI names in a {@link Race} document for display without modifying MongoDB.
     * Converted AI names retain the {@code " (AI)"} suffix and {@code is_player = false} so all AI markers stay intact.
     */
    @SuppressWarnings("unchecked")
    public static Race convertRace(Race race, Lookup lookup) {
        if (race == null || lookup == null) {
            return race;
        }

        Integer setupClassId = integer(race.getSetup() != null ? race.getSetup().get("VehicleClassId") : null);
        String fallbackClass = setupClassId != null && setupClassId > 0 ? lookup.vehicleClassForClassId(setupClassId) : null;
        Object trackObj = race.getSetup() != null ? race.getSetup().get("TrackId") : null;
        String trackName = trackObj != null ? lookup.trackNameForTrackId(String.valueOf(trackObj)) : null;

        List<DriverInput> inputs = new ArrayList<>();
        collectStageInputs(race.getStages(), "qualifying1", fallbackClass, lookup, inputs);
        collectStageInputs(race.getStages(), "race1", fallbackClass, lookup, inputs);
        collectParticipantInputs(race.getParticipants(), fallbackClass, lookup, inputs);

        Map<String, String> mapping = buildSessionMapping(inputs, trackName);
        if (mapping.isEmpty()) {
            return race;
        }

        applyToStageResults(race.getStages(), "qualifying1", fallbackClass, lookup, mapping);
        applyToStageResults(race.getStages(), "race1", fallbackClass, lookup, mapping);
        applyToParticipants(race.getParticipants(), fallbackClass, lookup, mapping);
        return race;
    }

    public static LiveTiming.Board convertLiveBoard(LiveTiming.Board board, Lookup lookup) {
        if (board == null || lookup == null || board.cars() == null || board.cars().isEmpty()) {
            return board;
        }

        String trackName = board.trackId() != null ? lookup.trackNameForTrackId(board.trackId()) : null;
        List<DriverInput> inputs = new ArrayList<>(board.cars().size());
        for (LiveTiming.Car car : board.cars()) {
            String carClass = lookup.vehicleClassForVehicleId(car.vehicleId());
            inputs.add(new DriverInput(car.name(), car.player(), carClass));
        }

        Map<String, String> mapping = buildSessionMapping(inputs, trackName);
        if (mapping.isEmpty()) {
            return board;
        }

        List<LiveTiming.Car> updated = new ArrayList<>(board.cars().size());
        for (LiveTiming.Car car : board.cars()) {
            String newName = car.name();
            if (!car.player()) {
                String normClass = normalizeClass(lookup.vehicleClassForVehicleId(car.vehicleId()));
                String mapped = mapping.get(lower(normClass) + ":" + lower(stripAiSuffix(car.name())));
                if (mapped != null) {
                    newName = mapped;
                }
            }
            updated.add(new LiveTiming.Car(
                    car.position(), newName, car.player(), car.lap(), car.sector(),
                    car.s1(), car.s2(), car.s3(), car.lastLap(), car.bestLap(),
                    car.state(), car.vehicleId()
            ));
        }
        return new LiveTiming.Board(board.stage(), board.trackId(), board.raceLength(), updated);
    }

    private static void collectStageInputs(Map<String, Object> stages, String stageName, String fallbackClass,
                                           Lookup lookup, List<DriverInput> out) {
        if (stages == null || !(stages.get(stageName) instanceof Map<?, ?> stage)) {
            return;
        }
        if (!(stage.get("results") instanceof List<?> results)) {
            return;
        }
        for (Object item : results) {
            if (!(item instanceof Map<?, ?> r)) {
                continue;
            }
            String name = str(r.get("name"));
            if (name == null) {
                continue;
            }
            boolean isPlayer = Boolean.TRUE.equals(r.get("is_player"));
            Integer vehicleId = r.get("attributes") instanceof Map<?, ?> attrs ? integer(attrs.get("VehicleId")) : null;
            String carClass = resolveClass(vehicleId, fallbackClass, lookup);
            out.add(new DriverInput(name, isPlayer, carClass));
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyToStageResults(Map<String, Object> stages, String stageName, String fallbackClass,
                                            Lookup lookup, Map<String, String> mapping) {
        if (stages == null || !(stages.get(stageName) instanceof Map<?, ?> stage)) {
            return;
        }
        if (!(stage.get("results") instanceof List<?> results)) {
            return;
        }
        for (Object item : results) {
            if (!(item instanceof Map<?, ?> rawMap)) {
                continue;
            }
            Map<String, Object> r = (Map<String, Object>) rawMap;
            if (Boolean.TRUE.equals(r.get("is_player"))) {
                continue;
            }
            String name = str(r.get("name"));
            if (name == null) {
                continue;
            }
            Integer vehicleId = r.get("attributes") instanceof Map<?, ?> attrs ? integer(attrs.get("VehicleId")) : null;
            String normClass = normalizeClass(resolveClass(vehicleId, fallbackClass, lookup));
            String mapped = mapping.get(lower(normClass) + ":" + lower(stripAiSuffix(name)));
            if (mapped != null) {
                r.put("name", mapped + " (AI)");
            }
        }
    }

    private static void collectParticipantInputs(Object participants, String fallbackClass,
                                                 Lookup lookup, List<DriverInput> out) {
        for (Map<?, ?> p : participantMaps(participants)) {
            String name = str(p.get("Name"));
            if (name == null) {
                continue;
            }
            Integer isPlayerInt = integer(p.get("IsPlayer"));
            boolean isPlayer = isPlayerInt != null && isPlayerInt != 0;
            String carClass = resolveClass(integer(p.get("VehicleId")), fallbackClass, lookup);
            out.add(new DriverInput(name, isPlayer, carClass));
        }
    }

    @SuppressWarnings("unchecked")
    private static void applyToParticipants(Object participants, String fallbackClass,
                                            Lookup lookup, Map<String, String> mapping) {
        for (Map<?, ?> rawMap : participantMaps(participants)) {
            Map<String, Object> p = (Map<String, Object>) rawMap;
            Integer isPlayerInt = integer(p.get("IsPlayer"));
            if (isPlayerInt != null && isPlayerInt != 0) {
                continue;
            }
            String name = str(p.get("Name"));
            if (name == null) {
                continue;
            }
            String normClass = normalizeClass(resolveClass(integer(p.get("VehicleId")), fallbackClass, lookup));
            String mapped = mapping.get(lower(normClass) + ":" + lower(stripAiSuffix(name)));
            if (mapped != null) {
                p.put("Name", mapped + " (AI)");
            }
        }
    }

    private static List<Map<?, ?>> participantMaps(Object participants) {
        List<Map<?, ?>> list = new ArrayList<>();
        if (participants instanceof Map<?, ?> map) {
            for (Object v : map.values()) {
                if (v instanceof Map<?, ?> m) {
                    list.add(m);
                }
            }
        } else if (participants instanceof List<?> items) {
            for (Object v : items) {
                if (v instanceof Map<?, ?> m) {
                    list.add(m);
                }
            }
        }
        return list;
    }

    private static String resolveClass(Integer vehicleId, String fallbackClass, Lookup lookup) {
        if (vehicleId != null) {
            String fromVehicle = lookup.vehicleClassForVehicleId(vehicleId);
            if (fromVehicle != null && !fromVehicle.isBlank()) {
                return fromVehicle;
            }
        }
        return fallbackClass;
    }

    private static String str(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }

    private static Integer integer(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }
}
