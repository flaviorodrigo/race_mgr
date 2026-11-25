package com.coop.racemgr.rotation;

import com.coop.racemgr.gameserver.SessionSchema;
import com.coop.racemgr.utils.RacemgrUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RacemgrRotationFileMakerTest {
    private static JsonNode json(String resource) throws Exception {
        try (InputStream is = RacemgrRotationFileMakerTest.class.getResourceAsStream(resource)) {
            return RacemgrUtils.JSON.readTree(is);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertsStringEnumsAndFlagsToNumericValuesForServerFile() throws Exception {
        SessionSchema.Schema schema = SessionSchema.build(
                json("/am2ds_lists/attributes_session.json").path("response"),
                json("/am2ds_lists/enums.json").path("response"),
                Map.of(
                        "privatesession", json("/am2ds_lists/enums_privatesession.json").path("response"),
                        "scheduledfcy", json("/am2ds_lists/enums_scheduledfcy.json").path("response")
                ),
                json("/am2ds_lists/flags_session.json").path("response")
        );

        Map<String, Object> defaultSetup = new LinkedHashMap<>();
        defaultSetup.put("VehicleClassId", "F-V8_Gen2");
        defaultSetup.put("DamageType", "FULL");
        defaultSetup.put("DamageScale", "MEDIUM");
        defaultSetup.put("ManualPitStops", "Manual");
        defaultSetup.put("QualifyPrivateSession", "Off");
        defaultSetup.put("Flags", "FORCE_SAME_VEHICLE_CLASS,MECHANICAL_FAILURES,FILL_SESSION_WITH_AI");

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("persist_index", true);
        config.put("defaultConfig", defaultSetup);
        config.put("rotation", List.of(Map.of("TrackId", "Laguna_Seca_2020", "DamageType", "PERFORMANCEIMPACTING")));

        Map<String, Object> ready = (Map<String, Object>) RacemgrRotationFileMaker.toServerReadyConfig(config, schema);
        Map<String, Object> readyDefault = (Map<String, Object>) ready.get("defaultConfig");

        assertEquals("F-V8_Gen2", readyDefault.get("VehicleClassId"));
        assertEquals(3, readyDefault.get("DamageType"));
        assertEquals(1, readyDefault.get("DamageScale"));
        assertEquals(1, readyDefault.get("ManualPitStops"));
        assertEquals(0, readyDefault.get("QualifyPrivateSession"));
        // MECHANICAL_FAILURES (262144) + FILL_SESSION_WITH_AI (131072) = 393216 (FORCE_SAME_VEHICLE_CLASS omitted)
        assertInstanceOf(Integer.class, readyDefault.get("Flags"));
        assertEquals(262144 + 131072, readyDefault.get("Flags"));

        List<Map<String, Object>> readyRounds = (List<Map<String, Object>>) ready.get("rotation");
        assertEquals("Laguna_Seca_2020", readyRounds.get(0).get("TrackId"));
        assertEquals(2, readyRounds.get(0).get("DamageType"));

        // Original config passed in is untouched
        assertEquals("FULL", defaultSetup.get("DamageType"));
        assertTrue(defaultSetup.get("Flags") instanceof String);
    }
}
