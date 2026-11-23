package com.coop.racemgr.rotation;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RotationValidatorTest {
    private static Object parse(String json) {
        return JsonMapper.shared().readValue(json, Object.class);
    }

    private static final String GOOD = """
            {"persist_index": true,
             "defaultConfig": {"PracticeLength": 0, "QualifyLength": 5, "RaceLength": 5, "VehicleClassId": "F-V12",
               "Flags": "ALLOW_CUSTOM_VEHICLE_SETUP,ABS_ALLOWED", "QualifyWeatherSlots": 2, "QualifyWeatherSlot1": "Clear"},
             "rotation": [{"TrackId": "Interlagos_GP"},
                          {"TrackId": "Buenos_Aires_Circuito_6", "RaceWeatherSlot1": "LightRain", "RaceLength": 8}]}
            """;

    @Test
    void acceptsARotationShapedLikeTheBundledOnes() {
        assertThat(RotationValidator.validate(parse(GOOD))).isNull();
        // The key the game server's file uses is accepted too
        assertThat(RotationValidator.validate(parse(GOOD.replace("defaultConfig", "default")))).isNull();
    }

    @Test
    void rejectsStructureProblems() {
        assertThat(RotationValidator.validate(null)).isNotNull();
        assertThat(RotationValidator.validate("text")).isNotNull();
        assertThat(RotationValidator.validate(parse("{\"defaultConfig\": {}, \"rotation\": []}"))).contains("1 to");
        assertThat(RotationValidator.validate(parse("{\"defaultConfig\": {}, \"rotation\": [{}]}"))).contains("needs a track");
        assertThat(RotationValidator.validate(parse("{\"defaultConfig\": {}, \"rotation\": [{\"TrackId\": \"A\"}], \"extra\": 1}"))).contains("Unknown rotation setting");
        assertThat(RotationValidator.validate(parse("{\"defaultConfig\": [], \"rotation\": [{\"TrackId\": \"A\"}]}"))).contains("must be an object");
    }

    @Test
    void rejectsUnknownSettingsAndUnsafeValues() {
        assertThat(RotationValidator.validate(parse(GOOD.replace("\"RaceLength\": 8", "\"ServerControlsTrack\": 1")))).contains("Unknown setting");
        assertThat(RotationValidator.validate(parse(GOOD.replace("Interlagos_GP", "../../etc/passwd")))).contains("Invalid value");
        assertThat(RotationValidator.validate(parse(GOOD.replace("Interlagos_GP", "A\\\"; os.execute(1)")))).contains("Invalid value");
        assertThat(RotationValidator.validate(parse(GOOD.replace("\"RaceLength\": 5,", "\"RaceLength\": -5,")))).contains("Invalid value");
        assertThat(RotationValidator.validate(parse(GOOD.replace("\"RaceLength\": 5,", "\"RaceLength\": 5.5,")))).contains("Invalid value");
        assertThat(RotationValidator.validate(parse(GOOD.replace("\"persist_index\": true", "\"persist_index\": \"yes\"")))).contains("persist_index");
    }

    @Test
    void capsTheNumberOfEntries() {
        List<Object> entries = new ArrayList<>();
        for (int i = 0; i < RotationValidator.MAX_ENTRIES + 1; i++) {
            entries.add(Map.of("TrackId", "Track_" + i));
        }
        assertThat(RotationValidator.validate(Map.of("defaultConfig", Map.of(), "rotation", entries))).contains("entries");
        assertThat(RotationValidator.validate(Map.of("defaultConfig", Map.of(), "rotation", entries.subList(0, RotationValidator.MAX_ENTRIES)))).isNull();
    }
}
