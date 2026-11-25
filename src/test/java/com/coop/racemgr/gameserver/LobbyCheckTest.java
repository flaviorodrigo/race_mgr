package com.coop.racemgr.gameserver;

import com.coop.racemgr.utils.RacemgrUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// Real server lists (src/test/resources/am2ds_lists) and a rotation like the one on a real server
class LobbyCheckTest {
    private static JsonNode list(String file) throws Exception {
        try (var in = LobbyCheckTest.class.getResourceAsStream("/am2ds_lists/" + file + ".json")) {
            return RacemgrUtils.JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8)).path("response");
        }
    }

    private static SessionSchema.Schema schema() throws Exception {
        return SessionSchema.build(list("attributes_session"), list("enums"),
                Map.of("privatesession", list("enums_privatesession"), "scheduledfcy", list("enums_scheduledfcy")), list("flags_session"));
    }

    private static LobbyCheck.Names names() throws Exception {
        Map<String, Long> tracks = new java.util.LinkedHashMap<>();
        list("tracks").path("list").forEach(t -> tracks.put(t.path("name").asString(), t.path("id").longValue()));
        Map<String, Long> classes = new java.util.LinkedHashMap<>();
        list("vehicle_classes").path("list").forEach(c -> classes.put(c.path("name").asString(), c.path("value").longValue()));
        return new LobbyCheck.Names(tracks, classes, Map.of());
    }

    // Names, as Race Manager writes them (the addon accepts names and numbers alike)
    private static final String ROTATION = """
            { "version": 7, "config": { "persist_index": true,
              "default": { "RaceLength": 15, "ManualPitStops": "Manual", "DamageType": "FULL", "VehicleClassId": "F-V8_Gen2",
                           "Flags": "ABS_ALLOWED,TCS_ALLOWED,FORCE_SAME_VEHICLE_CLASS,PIT_STOP_ERRORS_ALLOWED", "TrackId": "Interlagos_GP" },
              "rotation": [ { "TrackId": "Brasilia_Full" }, { "TrackId": "Interlagos_GP", "RaceLength": 20, "RemoveFlags": "TCS_ALLOWED" } ] } }
            """;

    private static long track(String name) throws Exception {
        return names().tracks().get(name);
    }

    private static Map<String, LobbyCheck.Row> rows(LobbyCheck.Result result) {
        return result.rows().stream().collect(Collectors.toMap(LobbyCheck.Row::name, r -> r));
    }

    @Test
    void findsTheRoundByTheLiveTrackAndShowsWhatDiffers() throws Exception {
        var schema = schema();
        long abs = schema.flags().stream().filter(f -> f.name().equals("ABS_ALLOWED")).findFirst().orElseThrow().value();
        long sameClass = schema.flags().stream().filter(f -> f.name().equals("FORCE_SAME_VEHICLE_CLASS")).findFirst().orElseThrow().value();
        long pitErrors = schema.flags().stream().filter(f -> f.name().equals("PIT_STOP_ERRORS_ALLOWED")).findFirst().orElseThrow().value();
        long fv8 = names().classes().get("F-V8_Gen2");
        // The lobby has round 2's track and length, but Auto pit control, and is missing ABS (the FORCE_ flag is the addon's)
        JsonNode status = RacemgrUtils.JSON.readTree("""
                {"state": "Running", "attributes": {"TrackId": %d, "RaceLength": 20, "ManualPitStops": 0, "DamageType": 3,
                 "VehicleClassId": %d, "Flags": %d}}""".formatted(track("Interlagos_GP"), fv8, sameClass | pitErrors));

        var result = LobbyCheck.compare(status, RacemgrUtils.jsonStrToObj(ROTATION), 1, schema, names());

        assertThat(result.sessionOpen()).isTrue();
        assertThat(result.round()).isEqualTo(2);
        assertThat(result.liveTrack()).isEqualTo("Interlagos_GP");
        var rows = rows(result);
        assertThat(rows.get("RaceLength").match()).isTrue();
        assertThat(rows.get("DamageType")).extracting(LobbyCheck.Row::expected, LobbyCheck.Row::live, LobbyCheck.Row::match)
                .containsExactly("FULL", "FULL", true);
        assertThat(rows.get("VehicleClassId").match()).isTrue();
        assertThat(rows.get("ManualPitStops")).extracting(LobbyCheck.Row::expected, LobbyCheck.Row::live, LobbyCheck.Row::match)
                .containsExactly("Manual", "Auto", false);
        // Round 2 takes TCS off the default flags; the lobby lacks ABS
        assertThat(rows.get("Flags").expected()).isEqualTo("ABS_ALLOWED,PIT_STOP_ERRORS_ALLOWED");
        assertThat(rows.get("Flags").live()).isEqualTo("PIT_STOP_ERRORS_ALLOWED");
        assertThat(rows.get("Flags").match()).isFalse();
        assertThat(abs).isPositive();
    }

    @Test
    void aSessionOnATrackOutsideTheRotationIsComparedWithTheDefaultsAndSaysSo() throws Exception {
        JsonNode status = RacemgrUtils.JSON.readTree("{\"state\": \"Running\", \"attributes\": {\"TrackId\": %d, \"RaceLength\": 15}}"
                .formatted(track("Adelaide_Historic")));
        var result = LobbyCheck.compare(status, RacemgrUtils.jsonStrToObj(ROTATION), null, schema(), names());
        assertThat(result.round()).isNull();
        assertThat(result.note()).contains("isn't in the rotation");
        assertThat(rows(result).get("RaceLength").match()).isTrue();
    }

    @Test
    void withNoSessionItShowsWhatTheNextLobbyShouldGet() throws Exception {
        JsonNode idle = RacemgrUtils.JSON.readTree("{\"state\": \"Idle\", \"attributes\": {\"TrackId\": 0}}");
        var result = LobbyCheck.compare(idle, RacemgrUtils.jsonStrToObj(ROTATION), 1, schema(), names());
        assertThat(result.sessionOpen()).isFalse();
        assertThat(result.round()).isEqualTo(2);
        assertThat(rows(result).get("RaceLength")).extracting(LobbyCheck.Row::expected, LobbyCheck.Row::live).containsExactly("20", null);
        assertThat(LobbyCheck.persistedIndex(RacemgrUtils.jsonStrToObj("{\"index\": 3}"))).isEqualTo(3);
        assertThat(LobbyCheck.compare(idle, null, null, schema(), names()).note()).contains("can't be read");
    }
}
