package com.coop.racemgr.controllers;

import com.coop.racemgr.RacemgrApplication;
import com.coop.racemgr.model.Race;
import com.coop.racemgr.model.RaceNotes;
import com.coop.racemgr.repositories.RaceMgrConfigRepository;
import com.coop.racemgr.repositories.RaceRepository;
import com.coop.racemgr.stats.AiPilotConverter;
import com.coop.racemgr.stats.GameServerLookup;
import com.coop.racemgr.utils.RacemgrUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import com.coop.racemgr.audit.AuditLog;
import lombok.Data;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RaceController {
    @Autowired
    private RaceRepository raceRepository;

    @Autowired
    private RaceMgrConfigRepository raceMgrConfigRepository;

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private GameServerLookup gameServerLookup;

    // The leaderboard UI loads every race for a session/rotation in one request
    private static final int MAX_PAGE_SIZE = 10000;

    private static final Logger logger = LogManager.getLogger(RacemgrApplication.class);

    @Data
    public static class VoidRequest {
        private Boolean voided;
    }

    /** ADMIN ENDPOINTS **/

    @PostMapping("/api/v1/admin/race/events/{raceId}/void")
    public ResponseEntity<Object> voidRace(@PathVariable String raceId, @RequestBody VoidRequest request, Authentication authentication) {
        if (!raceId.matches("^[0-9a-f]{64}$") || request.getVoided() == null) {
            return ResponseHandler.generateResponse("A race id and voided true/false are required", HttpStatus.BAD_REQUEST, null);
        }

        try {
            Race race = raceRepository.findItemByRaceId(raceId);
            if (race == null) {
                return ResponseHandler.generateResponse("Race not found", HttpStatus.NOT_FOUND, null);
            }

            race.setVoided(request.getVoided() ? Boolean.TRUE : null);
            raceRepository.save(race);
            auditLog.record(authentication.getName(), request.getVoided() ? "race voided" : "race restored", "race " + raceId.substring(0, 8));
            return ResponseHandler.generateResponse(request.getVoided() ? "Race voided" : "Race restored", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @Data
    public static class VideoRequest {
        private String url;
    }

    @Data
    public static class PenaltiesRequest {
        private java.util.List<RaceNotes.Penalty> penalties;
    }

    @PutMapping("/api/v1/admin/race/events/{raceId}/video")
    public ResponseEntity<Object> setVideo(@PathVariable String raceId, @RequestBody VideoRequest request, Authentication authentication) {
        String url = RaceNotes.cleanVideoUrl(request.getUrl());
        if (!raceId.matches("^[0-9a-f]{64}$") || url == null) {
            return ResponseHandler.generateResponse("The link must be an https YouTube or Twitch address", HttpStatus.BAD_REQUEST, null);
        }

        try {
            Race race = raceRepository.findItemByRaceId(raceId);
            if (race == null) {
                return ResponseHandler.generateResponse("Race not found", HttpStatus.NOT_FOUND, null);
            }

            race.setVideoUrl(url.isEmpty() ? null : url);
            raceRepository.save(race);
            auditLog.record(authentication.getName(), url.isEmpty() ? "race video removed" : "race video set", "race " + raceId.substring(0, 8));
            return ResponseHandler.generateResponse(url.isEmpty() ? "Video link removed" : "Video link saved", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    // Replaces the race's penalties. The standings follow: results are re-ordered when a race is shown.
    @PutMapping("/api/v1/admin/race/events/{raceId}/penalties")
    public ResponseEntity<Object> setPenalties(@PathVariable String raceId, @RequestBody PenaltiesRequest request, Authentication authentication) {
        if (!raceId.matches("^[0-9a-f]{64}$")) {
            return ResponseHandler.generateResponse("Race not found", HttpStatus.NOT_FOUND, null);
        }

        try {
            Race race = raceRepository.findItemByRaceId(raceId);
            if (race == null) {
                return ResponseHandler.generateResponse("Race not found", HttpStatus.NOT_FOUND, null);
            }

            var cleaned = RaceNotes.cleanPenalties(request.getPenalties(), driversOf(AiPilotConverter.convertRace(race, gameServerLookup)));
            if (cleaned == null) {
                return ResponseHandler.generateResponse("Each penalty needs a driver of this race and 1 to " + RaceNotes.MAX_PENALTY_SECONDS
                        + " seconds, or a disqualification (at most " + RaceNotes.MAX_PENALTIES + ")", HttpStatus.BAD_REQUEST, null);
            }

            race.setPenalties(cleaned.isEmpty() ? null : cleaned);
            raceRepository.save(race);
            auditLog.record(authentication.getName(), "race penalties", "race " + raceId.substring(0, 8) + ": " + cleaned.size() + " penalties");
            return ResponseHandler.generateResponse("Penalties saved", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    // The driver names in the race result
    @SuppressWarnings("unchecked")
    private static java.util.Set<String> driversOf(Race race) {
        java.util.Set<String> names = new java.util.HashSet<>();
        if (race.getStages() != null && race.getStages().get("race1") instanceof java.util.Map<?, ?> stage
                && stage.get("results") instanceof java.util.List<?> results) {
            for (Object r : results) {
                if (r instanceof java.util.Map<?, ?> m && m.get("name") instanceof String name) {
                    names.add(name);
                }
            }
        }
        return names;
    }

    /** PUBLIC ENDPOINTS **/

    // One race, for shareable links (/race/<id>). The id is the race's hash, never a database query fragment.
    @GetMapping("/api/v1/race/events/{raceId}")
    public ResponseEntity<Object> raceById(@PathVariable String raceId) {
        if (!raceId.matches("^[0-9a-f]{64}$")) {
            return ResponseHandler.generateResponse("Race not found", HttpStatus.NOT_FOUND, null);
        }

        try {
            Race race = raceRepository.findItemByRaceId(raceId);
            return race == null
                    ? ResponseHandler.generateResponse("Race not found", HttpStatus.NOT_FOUND, null)
                    : ResponseHandler.generateResponse("Race", HttpStatus.OK, AiPilotConverter.convertRace(race, gameServerLookup));
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @GetMapping("/api/v1/race/events")
    public ResponseEntity<Object> raceEventList(
            @RequestParam(required = false, defaultValue = "") String raceSessionId,
            @RequestParam(required = false, defaultValue = "") String raceRotationId,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "10") Integer limit
    ) {
        Pageable paging = RacemgrUtils.boundedPageRequest(page, limit, MAX_PAGE_SIZE);
        Page<Race> raceSessions;

        try {
            if (!raceSessionId.equals("") && !raceRotationId.equals("")) {
                // Filter by session and rotation
                logger.info("Filter by session and rotation");
                raceSessions = raceRepository.findAllBySessionIdAndRotationId(raceSessionId, raceRotationId, paging);
            } else if (!raceSessionId.equals("") && raceRotationId.equals("")) {
                logger.info("Filter by session");
                // Filter by session
                raceSessions = raceRepository.findAllBySessionId(raceSessionId, paging);
                // Filter by rotation
            } else if (raceSessionId.equals("") && !raceRotationId.equals("")) {
                logger.info("Filter by rotation");
                raceSessions = raceRepository.findAllByRotationId(raceRotationId, paging);
            } else {
                // No filter
                logger.info("Filter none");
                raceSessions = raceRepository.findAll(paging);
            }

            for (Race r : raceSessions.getContent()) {
                AiPilotConverter.convertRace(r, gameServerLookup);
            }
            return ResponseHandler.generateResponse("Race event list", HttpStatus.OK, PageResponse.of(raceSessions));
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }
}
