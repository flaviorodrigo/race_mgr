package com.coop.racemgr.controllers;

import com.coop.racemgr.model.Race;
import com.coop.racemgr.model.RaceSession;
import com.coop.racemgr.repositories.RaceRepository;
import com.coop.racemgr.repositories.RaceSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RaceIngestControllerTest {
    private static final String SAMPLE_PAYLOAD = """
            {
              "sessionName": "Single Player",
              "sessions": [
                {
                  "uid": "20261126_230000",
                  "stage": "Race1",
                  "started_at": "2026-11-26T23:00:00",
                  "finished_at": "2026-11-26T23:10:00",
                  "attrs": { "TrackId": 100, "VehicleClassId": -1027000403 },
                  "drivers": [
                    {
                      "name": "McRod", "is_player": true, "participantid": 0, "vehicle_id": -403158996,
                      "final_position": 1, "state": "Finished",
                      "laps": [{ "lap": 1, "time": 82000, "s1": 27000, "s2": 28000, "s3": 27000, "valid": true, "position": 1 }]
                    }
                  ],
                  "results": [
                    {
                      "position": 1, "participantid": 0, "refid": 1, "name": "McRod", "is_player": true,
                      "vehicle_id": -403158996, "best_lap_time": 82000, "total_time": 82000, "laps_complete": 1, "state": "Finished"
                    }
                  ]
                }
              ]
            }
            """;

    @Test
    void rejectsUnauthenticatedIngestWithoutValidToken() {
        RaceIngestController controller = new RaceIngestController();
        ReflectionTestUtils.setField(controller, "configuredIngestToken", "secret-token");
        var resp = controller.ingest(SAMPLE_PAYLOAD, "wrong", null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void ingestsSinglePlayerRaceWithStewardAuthAndDeduplicates() {
        RaceRepository raceRepo = mock(RaceRepository.class);
        RaceSessionRepository sessionRepo = mock(RaceSessionRepository.class);

        RaceSession sp = new RaceSession("Single Player", new ArrayList<>());
        sp.setId("sp-session-1");
        when(sessionRepo.findByName("Single Player")).thenReturn(sp);
        when(raceRepo.findItemByRaceId(any())).thenReturn(null).thenReturn(new Race());

        RaceIngestController controller = new RaceIngestController();
        ReflectionTestUtils.setField(controller, "raceRepository", raceRepo);
        ReflectionTestUtils.setField(controller, "raceSessionRepository", sessionRepo);

        var auth = new UsernamePasswordAuthenticationToken(
                "McRod", "n/a", List.of(new SimpleGrantedAuthority("ROLE_STEWARD")));

        var first = controller.ingest(SAMPLE_PAYLOAD, null, auth);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body1 = (Map<String, Object>) first.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> data1 = (Map<String, Object>) body1.get("data");
        assertThat(data1.get("imported")).isEqualTo(1);
        assertThat(data1.get("skipped")).isEqualTo(0);
        verify(raceRepo).save(any(Race.class));

        var second = controller.ingest(SAMPLE_PAYLOAD, null, auth);
        @SuppressWarnings("unchecked")
        Map<String, Object> body2 = (Map<String, Object>) second.getBody();
        @SuppressWarnings("unchecked")
        Map<String, Object> data2 = (Map<String, Object>) body2.get("data");
        assertThat(data2.get("imported")).isEqualTo(0);
        assertThat(data2.get("skipped")).isEqualTo(1);
    }

    @Test
    void createsSinglePlayerSessionWhenMissing() {
        RaceRepository raceRepo = mock(RaceRepository.class);
        RaceSessionRepository sessionRepo = mock(RaceSessionRepository.class);
        when(sessionRepo.findByName("Single Player")).thenReturn(null);
        when(sessionRepo.save(any(RaceSession.class))).thenAnswer(inv -> {
            RaceSession s = inv.getArgument(0);
            s.setId("created-sp-id");
            return s;
        });

        RaceIngestController controller = new RaceIngestController();
        ReflectionTestUtils.setField(controller, "raceRepository", raceRepo);
        ReflectionTestUtils.setField(controller, "raceSessionRepository", sessionRepo);
        ReflectionTestUtils.setField(controller, "configuredIngestToken", "good-token");

        var resp = controller.ingest(SAMPLE_PAYLOAD, "good-token", null);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(sessionRepo).save(any(RaceSession.class));
        verify(raceRepo).save(any(Race.class));
    }
}
