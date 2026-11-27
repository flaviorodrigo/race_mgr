package com.coop.racemgr.stats;

import com.coop.racemgr.model.Race;
import com.coop.racemgr.utils.RacemgrUtils;
import com.google.common.hash.Hashing;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the `ams2_stats` addon's output (an alternative to Reiza's sms_stats that also records every lap, sector
 * times and DNF states) into the same Race documents the rest of Race Manager already shows. A race is a finished
 * "Race1" session, with the "Qualifying1" session directly before it when there is one.
 *
 * The addon writes local times without a time zone, so the zone is a setting.
 */
public final class Ams2StatsImporter {
    private Ams2StatsImporter() {}

    public static List<Race> toRaces(JsonNode root, ZoneId zone) {
        return toRaces(root, zone, "ams2_stats:");
    }

    public static List<Race> toRaces(JsonNode root, ZoneId zone, String idPrefix) {
        List<Race> races = new ArrayList<>();
        JsonNode sessions = root.path("sessions");
        if (!sessions.isArray()) {
            return races;
        }

        for (int i = 0; i < sessions.size(); i++) {
            JsonNode session = sessions.get(i);
            if (!"Race1".equals(session.path("stage").asString(null)) || session.path("finished_at").isMissingNode()) {
                continue;
            }

            List<Map<String, Object>> results = classify(session.path("results"));
            // A race session nobody actually raced in (everyone stayed in the garage) isn't a race
            if (results.stream().noneMatch(r -> number(attributes(r).get("Lap")) > 0)) {
                continue;
            }

            JsonNode qualifying = i > 0 && "Qualifying1".equals(sessions.get(i - 1).path("stage").asString(null)) ? sessions.get(i - 1) : null;
            races.add(buildRace(session, results, qualifying, zone, idPrefix));
        }
        return races;
    }

    private static Race buildRace(JsonNode session, List<Map<String, Object>> results, JsonNode qualifying, ZoneId zone, String idPrefix) {
        Race race = new Race();
        String uid = session.path("uid").asString("");
        race.setRace_id(Hashing.sha256().hashString((idPrefix == null ? "ams2_stats:" : idPrefix) + uid, StandardCharsets.UTF_8).toString());
        race.setStart_time(String.valueOf(epoch(session.path("started_at"), zone)));
        race.setEnd_time(String.valueOf(epoch(session.path("finished_at"), zone)));
        race.setFinished(true);

        Map<String, Object> setup = new LinkedHashMap<>();
        session.path("attrs").properties().forEach(e -> setup.put(e.getKey(), RacemgrUtils.JSON.convertValue(e.getValue(), Object.class)));
        setup.put("RaceLength", results.stream().mapToInt(r -> number(attributes(r).get("Lap"))).max().orElse(0));
        race.setSetup(setup);

        Map<String, Object> participants = new LinkedHashMap<>();
        for (int n = 0; n < results.size(); n++) {
            Map<String, Object> row = results.get(n);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("Name", row.get("name"));
            p.put("RefId", row.get("refid"));
            p.put("IsPlayer", Boolean.FALSE.equals(row.get("is_player")) ? 0 : 1);
            Object vehicleId = attributes(row).get("VehicleId");
            if (vehicleId != null) {
                p.put("VehicleId", vehicleId);
            }
            participants.put(String.valueOf(n), p);
        }
        race.setParticipants(participants);
        race.setMembers(new LinkedHashMap<>());

        Map<String, Object> stages = new LinkedHashMap<>();
        stages.put("race1", stage(session, results, zone));
        if (qualifying != null) {
            List<Map<String, Object>> qualifyingResults = classify(qualifying.path("results"));
            if (!qualifyingResults.isEmpty()) {
                stages.put("qualifying1", stage(qualifying, qualifyingResults, zone));
            }
        }
        race.setStages(stages);
        return race;
    }

    private static Map<String, Object> stage(JsonNode session, List<Map<String, Object>> results, ZoneId zone) {
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("start_time", epoch(session.path("started_at"), zone));
        stage.put("end_time", epoch(session.path("finished_at"), zone));
        stage.put("events", new LinkedHashMap<>());
        stage.put("results", results);
        // Every lap with its sector times, for the sector and lap-by-lap views
        stage.put("drivers", RacemgrUtils.JSON.convertValue(session.path("drivers"), Object.class));
        return stage;
    }

    /**
     * Results in the shape sms_stats uses (name, participantid, attributes.RacePosition / FastestLapTime / TotalTime /
     * State), ordered by position. Drivers who retired have no position: they are placed after the others, the one who
     * got furthest first.
     */
    private static List<Map<String, Object>> classify(JsonNode results) {
        List<JsonNode> all = new ArrayList<>();
        if (results.isArray()) {
            results.forEach(all::add);
        }

        List<JsonNode> placed = new ArrayList<>(all.stream().filter(r -> r.path("position").isNumber()).toList());
        placed.sort(Comparator.comparingInt(r -> r.path("position").asInt()));
        List<JsonNode> unplaced = new ArrayList<>(all.stream().filter(r -> !r.path("position").isNumber()).toList());
        unplaced.sort(Comparator.comparingInt((JsonNode r) -> r.path("laps_complete").asInt(0)).reversed());

        List<Map<String, Object>> ordered = new ArrayList<>();
        int next = placed.isEmpty() ? 1 : placed.get(placed.size() - 1).path("position").asInt() + 1;
        for (JsonNode r : placed) {
            ordered.add(result(r, r.path("position").asInt(), ordered.size()));
        }
        for (JsonNode r : unplaced) {
            if (r.path("state").asString("").equals("InGarage") && r.path("laps_complete").asInt(0) == 0) {
                continue; // never drove
            }
            ordered.add(result(r, next++, ordered.size()));
        }
        return ordered;
    }

    private static Map<String, Object> result(JsonNode r, int position, int index) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("RacePosition", position);
        attributes.put("FastestLapTime", r.path("best_lap_time").asInt(0));
        attributes.put("TotalTime", r.path("total_time").asInt(0));
        attributes.put("Lap", r.path("laps_complete").asInt(0));
        attributes.put("State", r.path("state").asString(""));
        if (r.path("vehicle_id").isNumber()) {
            attributes.put("VehicleId", r.path("vehicle_id").asInt());
        }

        boolean isPlayer = !r.path("is_player").isBoolean() || r.path("is_player").asBoolean();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", r.path("name").asString("Unknown"));
        result.put("participantid", index);
        result.put("refid", r.path("refid").asInt(-1));
        result.put("is_player", isPlayer);
        result.put("attributes", attributes);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> result) {
        return (Map<String, Object>) result.get("attributes");
    }

    private static int number(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static long epoch(JsonNode isoLocal, ZoneId zone) {
        try {
            return LocalDateTime.parse(isoLocal.asString("")).atZone(zone).toEpochSecond();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }
}
