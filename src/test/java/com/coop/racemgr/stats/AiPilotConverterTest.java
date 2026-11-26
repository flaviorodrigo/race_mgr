package com.coop.racemgr.stats;

import com.coop.racemgr.gameserver.LiveTiming;
import com.coop.racemgr.model.Race;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPilotConverterTest {
    private static final AiPilotConverter.Lookup TEST_LOOKUP = new AiPilotConverter.Lookup() {
        @Override
        public String vehicleClassForVehicleId(Integer vehicleId) {
            return vehicleId != null && vehicleId == 1001 ? "FE-G1" : null;
        }

        @Override
        public String vehicleClassForClassId(Integer classId) {
            return classId != null && classId == 2001 ? "FE-G1" : null;
        }

        @Override
        public String trackNameForTrackId(String trackId) {
            return "999".equals(trackId) ? "Monza_2005" : null;
        }
    };

    @Test
    void mapsGenericAiToUniqueRealPilotsWithoutCollidingWithHumans() {
        List<AiPilotConverter.DriverInput> inputs = List.of(
                new AiPilotConverter.DriverInput("Michael Schumacher", true, "FE-G1"),
                new AiPilotConverter.DriverInput("Giancarlo Rampanelli (AI)", false, "FE-G1"),
                new AiPilotConverter.DriverInput("Dave Stephenson (AI)", false, "FE-G1"),
                new AiPilotConverter.DriverInput("Jose Lopez (AI)", false, "FE-G1")
        );

        Map<String, String> mapping = AiPilotConverter.buildSessionMapping(inputs, "Monza_2005");
        assertEquals(3, mapping.size());

        Set<String> assigned = new HashSet<>(mapping.values());
        assertEquals(3, assigned.size());
        assertFalse(assigned.contains("Michael Schumacher"));
    }

    @Test
    void convertsRaceDocumentKeepingAiSuffixAndPlayerFlags() {
        Race race = new Race();
        race.setSetup(Map.of("VehicleClassId", 2001, "TrackId", 999));

        Map<String, Object> p0 = new LinkedHashMap<>();
        p0.put("Name", "Human Player");
        p0.put("IsPlayer", 1);
        p0.put("VehicleId", 1001);

        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("Name", "Giancarlo Rampanelli (AI)");
        p1.put("IsPlayer", 0);
        p1.put("VehicleId", 1001);

        race.setParticipants(Map.of("0", p0, "1", p1));

        Map<String, Object> r0 = new LinkedHashMap<>();
        r0.put("name", "Human Player");
        r0.put("is_player", true);
        r0.put("attributes", Map.of("VehicleId", 1001));

        Map<String, Object> r1 = new LinkedHashMap<>();
        r1.put("name", "Giancarlo Rampanelli (AI)");
        r1.put("is_player", false);
        r1.put("attributes", Map.of("VehicleId", 1001));

        List<Map<String, Object>> results = new ArrayList<>();
        results.add(r0);
        results.add(r1);
        race.setStages(Map.of("race1", Map.of("results", results)));

        AiPilotConverter.convertRace(race, TEST_LOOKUP);

        assertEquals("Human Player", r0.get("name"));
        assertTrue((Boolean) r0.get("is_player"));

        String converted = (String) r1.get("name");
        assertNotEquals("Giancarlo Rampanelli (AI)", converted);
        assertTrue(converted.endsWith(" (AI)"), "Converted AI should keep (AI) suffix: " + converted);
        assertFalse((Boolean) r1.get("is_player"));
        assertEquals(converted, p1.get("Name"));
    }

    @Test
    void convertsLiveTimingBoardKeepingPlayerFalse() {
        LiveTiming.Board board = new LiveTiming.Board(
                "Race1",
                "999",
                10,
                List.of(
                        new LiveTiming.Car(1, "Human Player", true, 1, 1, null, null, null, null, null, "Racing", 1001),
                        new LiveTiming.Car(2, "Giancarlo Rampanelli (AI)", false, 1, 1, null, null, null, null, null, "Racing", 1001)
                )
        );

        LiveTiming.Board converted = AiPilotConverter.convertLiveBoard(board, TEST_LOOKUP);
        assertEquals("Human Player", converted.cars().get(0).name());
        assertTrue(converted.cars().get(0).player());

        assertNotEquals("Giancarlo Rampanelli (AI)", converted.cars().get(1).name());
        assertFalse(converted.cars().get(1).player());
    }

    // Class ids are signed 32-bit numbers and half of them are negative (F-V8_Gen2 is -1027000403). A race whose results
    // carry car ids the server's list doesn't know has to fall back to the session's class, whatever its sign.
    @Test
    void convertsResultsWhoseCarIdIsUnknownThroughTheSessionsNegativeClassId() {
        AiPilotConverter.Lookup lookup = new AiPilotConverter.Lookup() {
            @Override
            public String vehicleClassForVehicleId(Integer vehicleId) {
                return vehicleId != null && vehicleId == 1001 ? "FE-G1" : null;
            }

            @Override
            public String vehicleClassForClassId(Integer classId) {
                return classId != null && classId == -1027000403 ? "FE-G1" : null;
            }

            @Override
            public String trackNameForTrackId(String trackId) {
                return null;
            }
        };

        Race race = new Race();
        race.setSetup(Map.of("VehicleClassId", -1027000403, "TrackId", 999));
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("Name", "Giancarlo Rampanelli (AI)");
        p1.put("IsPlayer", 0);
        p1.put("VehicleId", 1001);
        race.setParticipants(Map.of("1", p1));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "Giancarlo Rampanelli  (AI)");
        result.put("is_player", false);
        result.put("attributes", Map.of("VehicleId", 468530913)); // not in the list
        race.setStages(Map.of("race1", Map.of("results", List.of(result))));

        AiPilotConverter.convertRace(race, lookup);

        String converted = (String) result.get("name");
        assertNotEquals("Giancarlo Rampanelli  (AI)", converted);
        assertTrue(converted.endsWith(" (AI)"), converted);
        assertEquals(converted, p1.get("Name"), "the results and the participants get the same real name");
    }

    private Race raceWithUnknownResultCarIds(Map<String, Object> result, Map<String, Object> participant) {
        Race race = new Race();
        race.setSetup(Map.of("TrackId", 999)); // no class to fall back on
        race.setParticipants(Map.of("2", participant));
        race.setStages(Map.of("race1", Map.of("results", List.of(result))));
        return race;
    }

    @Test
    void showsTheParticipantsCarWhenTheResultsIdIsNotInTheListAndTakesItsClass() {
        Map<String, Object> participant = new LinkedHashMap<>();
        participant.put("Name", "Giancarlo Rampanelli (AI)");
        participant.put("IsPlayer", 0);
        participant.put("VehicleId", 1001); // known: FE-G1
        Map<String, Object> attributes = new LinkedHashMap<>(Map.of("VehicleId", 468530913)); // unknown
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "Giancarlo Rampanelli  (AI)");
        result.put("is_player", false);
        result.put("participantid", 2);
        result.put("attributes", attributes);

        AiPilotConverter.convertRace(raceWithUnknownResultCarIds(result, participant), TEST_LOOKUP);

        assertEquals(1001, attributes.get("VehicleId"));
        assertNotEquals("Giancarlo Rampanelli  (AI)", result.get("name"), "its class is found through the participant's car");
    }

    @Test
    void leavesAResultsCarAloneWhenItsIdIsKnownOrTheParticipantsIsNot() {
        Map<String, Object> participant = new LinkedHashMap<>(Map.of("Name", "X (AI)", "IsPlayer", 0, "VehicleId", 7777)); // unknown too
        Map<String, Object> attributes = new LinkedHashMap<>(Map.of("VehicleId", 468530913));
        Map<String, Object> result = new LinkedHashMap<>(Map.of("name", "X (AI)", "is_player", false, "participantid", 2, "attributes", attributes));
        AiPilotConverter.convertRace(raceWithUnknownResultCarIds(result, participant), TEST_LOOKUP);
        assertEquals(468530913, attributes.get("VehicleId"));

        Map<String, Object> known = new LinkedHashMap<>(Map.of("VehicleId", 1001));
        Map<String, Object> result2 = new LinkedHashMap<>(Map.of("name", "X (AI)", "is_player", false, "participantid", 2, "attributes", known));
        Map<String, Object> participant2 = new LinkedHashMap<>(Map.of("Name", "X (AI)", "IsPlayer", 0, "VehicleId", 1001));
        AiPilotConverter.convertRace(raceWithUnknownResultCarIds(result2, participant2), TEST_LOOKUP);
        assertEquals(1001, known.get("VehicleId"));
    }

    @Test
    void aConvertedCopyLeavesTheStoredRaceUntouched() {
        Race race = new Race();
        race.setSetup(Map.of("VehicleClassId", 2001, "TrackId", 999));
        Map<String, Object> ai = new LinkedHashMap<>(Map.of("name", "Giancarlo Rampanelli (AI)", "is_player", false,
                "attributes", new LinkedHashMap<>(Map.of("VehicleId", 1001))));
        race.setStages(Map.of("race1", Map.of("results", new ArrayList<>(List.of(ai)))));

        Race shown = AiPilotConverter.convertedCopy(race, TEST_LOOKUP);

        assertEquals("Giancarlo Rampanelli (AI)", ai.get("name"), "the stored race keeps its generic name");
        @SuppressWarnings("unchecked")
        var shownResults = (List<Map<String, Object>>) ((Map<String, Object>) shown.getStages().get("race1")).get("results");
        assertNotEquals("Giancarlo Rampanelli (AI)", shownResults.get(0).get("name"));
    }

    @Test
    void mapsCarSpecificReizaDriversDirectlyAndReservesTheirRealNames() {
        List<AiPilotConverter.DriverInput> inputs = List.of(
                new AiPilotConverter.DriverInput("Alejandro Fernandez  (AI)", false, "F-V8_Gen2"),
                new AiPilotConverter.DriverInput("Giancarlo Rampanelli (AI)", false, "F-V8_Gen2"),
                new AiPilotConverter.DriverInput("Dave Stephenson (AI)", false, "F-V8_Gen2")
        );

        Map<String, String> mapping = AiPilotConverter.buildSessionMapping(inputs, "Monza_2005");
        assertEquals("Fernando Alonso", mapping.get("f-v8_gen2:alejandro fernandez"));

        Set<String> assigned = new HashSet<>(mapping.values());
        assertEquals(3, assigned.size());
    }
}
