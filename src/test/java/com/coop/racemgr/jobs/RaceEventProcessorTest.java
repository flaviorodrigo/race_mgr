package com.coop.racemgr.jobs;

import com.coop.racemgr.model.Race;
import com.coop.racemgr.model.RaceMgrConfig;
import com.coop.racemgr.repositories.RaceMgrConfigRepository;
import com.coop.racemgr.repositories.RaceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class RaceEventProcessorTest {
    @AfterEach
    void clear() {
        System.clearProperty("RM_PATH_TO_DEDICATED_SERVER");
    }

    @Test
    void whatRaceManagerAddsToARaceNeverComesFromTheStatsFile(@TempDir Path server) throws Exception {
        // A doctored sms_stats file that also tries to set Race Manager's own fields
        Files.createDirectories(server.resolve("lua_config"));
        Files.writeString(server.resolve("lua_config/sms_stats_data.json"), """
                // Persistent data for addon 'sms_stats'
                { "stats": { "history": [ {
                    "id": "someone-elses-race", "voided": true, "videoUrl": "javascript:alert(1)",
                    "penalties": [ { "driver": "A", "seconds": 600, "disqualify": false, "note": "x" } ],
                    "race_session_id": "other-session",
                    "index": 7, "start_time": 1700000000, "end_time": 1700003600, "finished": true,
                    "setup": { "TrackId": 1 }, "participants": {}, "members": {}, "stages": {} } ] } }
                """);
        System.setProperty("RM_PATH_TO_DEDICATED_SERVER", server.toString());

        RaceRepository races = Mockito.mock(RaceRepository.class);
        RaceMgrConfigRepository configs = Mockito.mock(RaceMgrConfigRepository.class);
        var config = new RaceMgrConfig("my-session", "my-rotation");
        Mockito.when(configs.findItemById("1")).thenReturn(config);

        new RaceEventProcessor(races, configs).processRaces();

        var saved = ArgumentCaptor.forClass(Race.class);
        Mockito.verify(races).save(saved.capture());
        Race race = saved.getValue();
        assertThat(race.getId()).isNull();
        assertThat(race.getVoided()).isNull();
        assertThat(race.getPenalties()).isNull();
        assertThat(race.getVideoUrl()).isNull();
        assertThat(race.getRace_session_id()).isEqualTo("my-session");
        assertThat(race.getRace_rotation_id()).isEqualTo("my-rotation");
        assertThat(race.getRace_id()).matches("^[0-9a-f]{64}$");
    }
}
