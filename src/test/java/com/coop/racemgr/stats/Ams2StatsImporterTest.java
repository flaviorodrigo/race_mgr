package com.coop.racemgr.stats;

import com.coop.racemgr.model.Race;
import com.coop.racemgr.utils.RacemgrUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Uses a real ams2_stats file (driver names and ids replaced): two race weekends, one with a disqualification
// and a retirement, plus lobby ("Allocating") and empty practice sessions that must be ignored.
class Ams2StatsImporterTest {
    private static JsonNode fixture() throws Exception {
        try (var in = Ams2StatsImporterTest.class.getResourceAsStream("/ams2_stats_sample.json")) {
            return RacemgrUtils.JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Race race, String stage) {
        return (List<Map<String, Object>>) ((Map<String, Object>) race.getStages().get(stage)).get("results");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attrs(Map<String, Object> result) {
        return (Map<String, Object>) result.get("attributes");
    }

    @Test
    void importsOneRacePerFinishedRaceSessionAndIgnoresTheRest() throws Exception {
        List<Race> races = Ams2StatsImporter.toRaces(fixture(), ZoneOffset.UTC);

        assertThat(races).hasSize(2);
        assertThat(races).extracting(Race::getRace_id).doesNotHaveDuplicates().allMatch(id -> id.matches("^[0-9a-f]{64}$"));
        assertThat(races).allMatch(r -> Boolean.TRUE.equals(r.getFinished()));
        // Ids are stable, so importing again never duplicates a race
        assertThat(Ams2StatsImporter.toRaces(fixture(), ZoneOffset.UTC)).extracting(Race::getRace_id)
                .containsExactlyElementsOf(races.stream().map(Race::getRace_id).toList());
    }

    @Test
    void mapsTheRaceIntoTheShapeTheRestOfTheAppReads() throws Exception {
        Race race = Ams2StatsImporter.toRaces(fixture(), ZoneOffset.UTC).get(0);

        assertThat(race.getSetup()).containsEntry("TrackId", 1534602052L).containsEntry("RaceLength", 17);
        assertThat(race.getStages()).containsOnlyKeys("race1", "qualifying1");
        assertThat(Long.parseLong(race.getStart_time())).isEqualTo(LocalDateTime.parse("2026-07-05T16:22:14").toEpochSecond(ZoneOffset.UTC));
        assertThat(Long.parseLong(race.getEnd_time())).isEqualTo(LocalDateTime.parse("2026-07-05T16:57:57").toEpochSecond(ZoneOffset.UTC));
        assertThat(race.getParticipants()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) race.getParticipants())).hasSize(6);

        var race1 = results(race, "race1");
        assertThat(race1).extracting(r -> attrs(r).get("RacePosition")).containsExactly(1, 2, 3, 4, 6, 7);
        assertThat(race1.get(0)).containsEntry("name", "Driver 1");
        assertThat(attrs(race1.get(0))).containsEntry("FastestLapTime", 103869).containsEntry("TotalTime", 1822962).containsEntry("State", "Finished");
        assertThat(results(race, "qualifying1")).extracting(r -> attrs(r).get("FastestLapTime")).first().isEqualTo(103797);
    }

    @Test
    void keepsDisqualifiedAndRetiredDriversAndPlacesTheRetirementLast() throws Exception {
        var race1 = results(Ams2StatsImporter.toRaces(fixture(), ZoneOffset.UTC).get(0), "race1");

        assertThat(race1).extracting(r -> attrs(r).get("State")).containsExactly("Finished", "Finished", "Finished", "Finished", "Disqualified", "Retired");
        // The retired driver has no position in the file: given the next one, after everybody who has one
        assertThat(attrs(race1.get(5))).containsEntry("RacePosition", 7).containsEntry("TotalTime", 0).containsEntry("Lap", 10);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsEveryLapWithItsSectorTimes() throws Exception {
        Race race = Ams2StatsImporter.toRaces(fixture(), ZoneOffset.UTC).get(0);
        var drivers = (List<Map<String, Object>>) ((Map<String, Object>) race.getStages().get("race1")).get("drivers");
        var winner = drivers.get(0);

        assertThat(winner).containsEntry("name", "Driver 1").containsEntry("best_s1", 39836L).containsEntry("best_s2", 32458L).containsEntry("best_s3", 31553L);
        var laps = (List<Map<String, Object>>) winner.get("laps");
        assertThat(laps).hasSize(17);
        assertThat(laps.get(1)).containsKeys("lap", "time", "s1", "s2", "s3", "valid", "position");
    }

    @Test
    void theTimeZoneDecidesWhenARaceHappened() throws Exception {
        long utc = Long.parseLong(Ams2StatsImporter.toRaces(fixture(), ZoneOffset.UTC).get(0).getStart_time());
        long newYork = Long.parseLong(Ams2StatsImporter.toRaces(fixture(), ZoneId.of("America/New_York")).get(0).getStart_time());
        // Local 16:22 in New York (UTC-4 in July) is four hours later than 16:22 UTC
        assertThat(newYork - utc).isEqualTo(4 * 3600);
    }

    @Test
    void toleratesEmptyOrOddFiles() throws Exception {
        assertThat(Ams2StatsImporter.toRaces(RacemgrUtils.JSON.readTree("{}"), ZoneOffset.UTC)).isEmpty();
        assertThat(Ams2StatsImporter.toRaces(RacemgrUtils.JSON.readTree("{\"sessions\": \"x\"}"), ZoneOffset.UTC)).isEmpty();
        // A race session nobody drove in is not a race
        String idle = "{\"sessions\":[{\"uid\":\"1\",\"stage\":\"Race1\",\"started_at\":\"2026-01-01T10:00:00\",\"finished_at\":\"2026-01-01T10:30:00\","
                + "\"attrs\":{},\"drivers\":[],\"results\":[{\"name\":\"A\",\"refid\":1,\"state\":\"InGarage\"}]}]}";
        assertThat(Ams2StatsImporter.toRaces(RacemgrUtils.JSON.readTree(idle), ZoneOffset.UTC)).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesAiIsPlayerFlagAndVehicleIdWhenPresent() throws Exception {
        String json = """
                {
                  "sessions": [{
                    "uid": "20261125_220000",
                    "stage": "Race1",
                    "started_at": "2026-11-25T22:00:00",
                    "finished_at": "2026-11-25T22:15:00",
                    "results": [
                      {"position": 1, "name": "McRod", "refid": 1024, "is_player": true, "vehicle_id": 55, "laps_complete": 5, "best_lap_time": 70000, "total_time": 350000, "state": "Finished"},
                      {"position": 2, "name": "Jose Lopez (AI)", "refid": 1024, "is_player": false, "vehicle_id": 56, "laps_complete": 5, "best_lap_time": 71000, "total_time": 355000, "state": "Finished"}
                    ]
                  }]
                }
                """;
        Race race = Ams2StatsImporter.toRaces(RacemgrUtils.JSON.readTree(json), ZoneOffset.UTC).get(0);
        var race1 = results(race, "race1");
        assertThat(race1.get(0)).containsEntry("is_player", true);
        assertThat(race1.get(1)).containsEntry("is_player", false).containsEntry("name", "Jose Lopez (AI)");
        assertThat(attrs(race1.get(1))).containsEntry("VehicleId", 56);

        var participants = (Map<String, Map<String, Object>>) race.getParticipants();
        assertThat(participants.get("0")).containsEntry("IsPlayer", 1).containsEntry("VehicleId", 55);
        assertThat(participants.get("1")).containsEntry("IsPlayer", 0).containsEntry("VehicleId", 56);
    }
}
