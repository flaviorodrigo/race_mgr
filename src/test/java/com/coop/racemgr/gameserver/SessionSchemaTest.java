package com.coop.racemgr.gameserver;

import com.coop.racemgr.utils.RacemgrUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

// The lists come from a real AMS2 dedicated server (src/test/resources/am2ds_lists)
class SessionSchemaTest {
    private static JsonNode list(String file) throws Exception {
        try (var in = SessionSchemaTest.class.getResourceAsStream("/am2ds_lists/" + file + ".json")) {
            return RacemgrUtils.JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8)).path("response");
        }
    }

    private static SessionSchema.Schema schema() throws Exception {
        return SessionSchema.build(list("attributes_session"), list("enums"),
                Map.of("privatesession", list("enums_privatesession"), "scheduledfcy", list("enums_scheduledfcy")), list("flags_session"));
    }

    @Test
    void describesEveryWritableSessionSettingExceptServerControls() throws Exception {
        var names = schema().attributes().stream().map(SessionSchema.Attribute::name).toList();
        assertThat(names).hasSize(78).contains("GridSize", "OpponentDifficulty", "RaceMandatoryPitStops", "DisablePitstopRefuelling", "RaceWeatherSlot4")
                .noneMatch(n -> n.startsWith("ServerControls"))
                .doesNotContain("SessionStage", "SessionState"); // read-only
    }

    @Test
    void picksTheRightEditorForEachSetting() throws Exception {
        Map<String, SessionSchema.Attribute> byName = schema().attributes().stream().collect(Collectors.toMap(SessionSchema.Attribute::name, a -> a));
        assertThat(byName.get("Flags").kind()).isEqualTo("flags");
        assertThat(byName.get("TrackId").kind()).isEqualTo("track");
        assertThat(byName.get("VehicleClassId").kind()).isEqualTo("vehicleClass");
        assertThat(byName.get("MultiClassSlot3").kind()).isEqualTo("vehicleClass");
        assertThat(byName.get("VehicleModelId").kind()).isEqualTo("vehicle");
        assertThat(byName.get("DamageType")).extracting(SessionSchema.Attribute::kind, SessionSchema.Attribute::enumName).containsExactly("enum", "damage");
        assertThat(byName.get("RaceWeatherSlot1").enumName()).isEqualTo("weather");
        assertThat(byName.get("QualifyPrivateSession").enumName()).isEqualTo("privatesession");
        // The game's description points this one at the weather list; it is a Live Track preset
        assertThat(byName.get("PracticeLiveTrackPreset").enumName()).isEqualTo("livetrack_preset");
        assertThat(byName.get("RaceLiveTrackPreset").enumName()).isEqualTo("livetrack_preset");
        assertThat(byName.get("RaceMandatoryPitStops").kind()).isEqualTo("toggle");
        assertThat(byName.get("RaceFormationLap").kind()).isEqualTo("toggle");
        assertThat(byName.get("FullCourseYellows").kind()).isEqualTo("toggle");
        assertThat(byName.get("OpponentDifficulty").kind()).isEqualTo("number");
        assertThat(byName.get("RaceLength").kind()).isEqualTo("number");
    }

    @Test
    void carriesTheOptionListsAndFlags() throws Exception {
        var schema = schema();
        assertThat(schema.enums().get("weather")).extracting(SessionSchema.Option::name).contains("Clear", "LightRain", "Storm");
        assertThat(schema.enums().get("weather")).anyMatch(o -> o.name().equals("Clear") && o.value() == -934211870L);
        assertThat(schema.enums().get("damage")).extracting(SessionSchema.Option::name).containsExactly("OFF", "VISUAL_ONLY", "PERFORMANCEIMPACTING", "FULL");
        assertThat(schema.flags()).hasSize(25).anyMatch(f -> f.name().equals("ABS_ALLOWED") && f.value() == 32);
    }

    @Test
    void theRotationCheckAllowsExactlyTheSettingsTheServerDescribes() throws Exception {
        var described = schema().attributes().stream().map(SessionSchema.Attribute::name).collect(Collectors.toSet());
        assertThat(com.coop.racemgr.rotation.RotationValidator.allowedSettings()).containsExactlyInAnyOrderElementsOf(described);
    }
}
