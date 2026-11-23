package com.coop.racemgr.gameserver;

import com.coop.racemgr.model.Race;
import com.coop.racemgr.utils.RacemgrUtils;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameServerJsonTest {
    @Test
    void parsesListResponses() throws Exception {
        List<Object> tracks = GameServerProxy.parseListResponse(
                "{\"response\":{\"list\":[{\"id\":1,\"name\":\"Interlagos\"},{\"id\":2,\"name\":\"Kart Track\"}]}}");

        assertThat(tracks).hasSize(2);
        assertThat(RacemgrUtils.getValueFromObj("name", tracks.get(0))).isEqualTo("Interlagos");
        // Callers remove elements from the list
        tracks.remove(0);
        assertThat(tracks).hasSize(1);
        // Whole numbers keep the Long type json-simple produced
        assertThat(((Map<?, ?>) tracks.get(0)).get("id")).isInstanceOf(Long.class);
    }

    @Test
    void rejectsUnexpectedListResponses() {
        assertThatThrownBy(() -> GameServerProxy.parseListResponse("{\"response\":{}}"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void parsesJsonIshStatsFileWithCommentsAndTrailingCommas() throws Exception {
        String file = "-- sms_stats data, written by the plugin\n"
                + "-- generated: today\n"
                + "{\n"
                + "  \"stats\": {\n"
                + "    // comment inside\n"
                + "    \"history\": [\n"
                + "      { \"index\": 1, \"start_time\": \"2026-01-01T10:00:00\", \"end_time\": \"2026-01-01T10:30:00\",\n"
                + "        \"finished\": true,\n"
                + "        \"members\": { \"1\": { \"name\": \"Driver\", \"steamid\": \"765\" } },\n"
                + "        \"participants\": [ { \"name\": \"Driver\" }, ],\n"
                + "        \"setup\": { \"TrackId\": 1234, \"GridSize\": 12 },\n"
                + "        \"stages\": { \"race1\": { \"time\": 1800.5 } },\n"
                + "      },\n"
                + "    ],\n"
                + "  },\n"
                + "}\n"
                + "-- trailer\n";

        JsonNode stats = GameServerProxy.parseStats(file);
        JsonNode history = stats.path("history");
        assertThat(history.isArray()).isTrue();

        Race race = RacemgrUtils.JSON.treeToValue(history.get(0), Race.class);
        assertThat(race.getIndex()).isEqualTo(1);
        assertThat(race.getFinished()).isTrue();
        assertThat(race.getMembers()).containsKey("1");
        assertThat(race.getSetup().get("TrackId")).isEqualTo(1234L);
        assertThat(race.getStages()).containsKey("race1");

        race.setRaceId();
        assertThat(race.getRace_id()).hasSize(64);
    }

    @Test
    void statsFileWithoutJsonFails() {
        assertThatThrownBy(() -> GameServerProxy.parseStats("-- empty\n"))
                .isInstanceOf(java.io.IOException.class);
        assertThatThrownBy(() -> GameServerProxy.parseStats("{\"other\": {}}"))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void jsonStrToObjReturnsNullForInvalidJson() {
        assertThat(RacemgrUtils.jsonStrToObj("{not json")).isNull();
        assertThat(RacemgrUtils.getValueFromObj("status", RacemgrUtils.jsonStrToObj("{\"status\":\"stop\"}")))
                .isEqualTo("stop");
        assertThat(RacemgrUtils.getValueFromObj("status", RacemgrUtils.jsonStrToObj("{}"))).isNull();
    }
}
