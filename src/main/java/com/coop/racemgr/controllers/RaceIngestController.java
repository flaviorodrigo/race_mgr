package com.coop.racemgr.controllers;

import com.coop.racemgr.audit.AuditLog;
import com.coop.racemgr.model.Race;
import com.coop.racemgr.model.RaceSession;
import com.coop.racemgr.repositories.RaceRepository;
import com.coop.racemgr.repositories.RaceSessionRepository;
import com.coop.racemgr.security.SessionCookies;
import com.coop.racemgr.stats.AiPilotConverter;
import com.coop.racemgr.stats.Ams2StatsImporter;
import com.coop.racemgr.stats.Ams2StatsSource;
import com.coop.racemgr.stats.GameServerLookup;
import com.coop.racemgr.utils.RacemgrUtils;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ingests single-player (or exported) {@code ams2_stats}-format race payloads into user-owned single-player sessions,
 * keeps them separate from multiplayer leaderboards, and hands out the signed-in user's bearer JWT for SimHub.
 */
@RestController
public class RaceIngestController {
    private static final String DEFAULT_SP_SESSION = "Single Player";
    private static final int MAX_PAGE_SIZE = 10000;

    @Autowired
    private RaceRepository raceRepository;

    @Autowired
    private RaceSessionRepository raceSessionRepository;

    @Autowired(required = false)
    private GameServerLookup gameServerLookup;

    @Autowired(required = false)
    private AuditLog auditLog;

    @Value("${RM_INGEST_TOKEN:}")
    private String configuredIngestToken;

    @GetMapping("/api/v1/admin/ingest/token")
    public ResponseEntity<Object> ingestToken(HttpServletRequest request, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseHandler.generateResponse("Unauthorized", HttpStatus.UNAUTHORIZED, null);
        }
        String token = SessionCookies.tokenFrom(request);
        if (token == null || token.isBlank()) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                token = auth.substring(7);
            }
        }
        if (token == null || token.isBlank()) {
            return ResponseHandler.generateResponse("No active session token", HttpStatus.UNAUTHORIZED, null);
        }
        return ResponseHandler.generateResponse("OK", HttpStatus.OK, Map.of(
                "token", token,
                "username", authentication.getName()
        ));
    }

    @GetMapping("/api/v1/admin/single-player/sessions")
    public ResponseEntity<Object> mySinglePlayerSessions(
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "100") Integer limit,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseHandler.generateResponse("Unauthorized", HttpStatus.UNAUTHORIZED, null);
        }
        Pageable paging = RacemgrUtils.boundedPageRequest(page, limit, MAX_PAGE_SIZE);
        Page<RaceSession> sessions = raceSessionRepository.findAllSinglePlayerByOwner(authentication.getName(), paging);
        return ResponseHandler.generateResponse("Single player sessions", HttpStatus.OK, PageResponse.of(sessions));
    }

    @GetMapping("/api/v1/admin/single-player/races")
    public ResponseEntity<Object> mySinglePlayerRaces(
            @RequestParam(required = false, defaultValue = "") String raceSessionId,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "1000") Integer limit,
            Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseHandler.generateResponse("Unauthorized", HttpStatus.UNAUTHORIZED, null);
        }
        Pageable paging = RacemgrUtils.boundedPageRequest(page, limit, MAX_PAGE_SIZE);
        Page<Race> races = raceSessionId.isBlank()
                ? raceRepository.findAllSinglePlayerByOwner(authentication.getName(), paging)
                : raceRepository.findAllSinglePlayerByOwnerAndSessionId(authentication.getName(), raceSessionId, paging);
        if (gameServerLookup != null) {
            for (Race r : races.getContent()) {
                AiPilotConverter.convertRace(r, gameServerLookup);
            }
        }
        return ResponseHandler.generateResponse("Single player races", HttpStatus.OK, PageResponse.of(races));
    }

    @PostMapping("/api/v1/race/ingest")
    public ResponseEntity<Object> ingest(
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Ingest-Token", required = false) String ingestToken,
            Authentication authentication) {
        if (!isAuthorized(ingestToken, authentication)) {
            return ResponseHandler.generateResponse("Unauthorized", HttpStatus.UNAUTHORIZED, null);
        }

        JsonNode root;
        try {
            root = RacemgrUtils.JSON.readTree(rawBody);
        } catch (Exception e) {
            return ResponseHandler.generateResponse("Invalid JSON payload", HttpStatus.BAD_REQUEST, null);
        }

        String owner = authentication != null ? authentication.getName() : "token";
        String requestedSession = root.path("sessionName").asString(DEFAULT_SP_SESSION).trim();
        String sessionName = requestedSession.isEmpty() ? DEFAULT_SP_SESSION : requestedSession;
        RaceSession session = findOrCreateSession(sessionName, owner);

        List<Race> races = Ams2StatsImporter.toRaces(root, Ams2StatsSource.zone(), "ams2_stats_sp:" + owner + ":");
        int imported = 0;
        int skipped = 0;
        for (Race race : races) {
            race.setRaceSessionId(session.getId());
            race.setSinglePlayer(true);
            race.setOwner(owner);
            if (raceRepository.findItemByRaceId(race.getRace_id()) != null) {
                skipped++;
                continue;
            }
            raceRepository.save(race);
            imported++;
        }

        if (auditLog != null && imported > 0) {
            auditLog.record(owner, "races ingested", imported + " single-player race(s) into " + session.getName());
        }

        return ResponseHandler.generateResponse("Ingested", HttpStatus.OK, Map.of(
                "imported", imported,
                "skipped", skipped,
                "sessionId", session.getId(),
                "sessionName", session.getName()
        ));
    }

    private boolean isAuthorized(String ingestToken, Authentication authentication) {
        if (configuredIngestToken != null && !configuredIngestToken.isBlank()
                && ingestToken != null && !ingestToken.isBlank()
                && MessageDigest.isEqual(
                        configuredIngestToken.getBytes(StandardCharsets.UTF_8),
                        ingestToken.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        for (GrantedAuthority auth : authentication.getAuthorities()) {
            String role = auth.getAuthority();
            if ("ROLE_STEWARD".equals(role) || "ROLE_ADMIN".equals(role) || "ROLE_OWNER".equals(role)) {
                return true;
            }
        }
        return false;
    }

    private RaceSession findOrCreateSession(String name, String owner) {
        RaceSession existing = raceSessionRepository.findByNameAndOwner(name, owner);
        if (existing != null) {
            return existing;
        }
        String uniqueName = raceSessionRepository.findByName(name) == null
                ? name
                : name + " (" + owner.replace("synology:", "") + ")";
        RaceSession existingWithSuffix = raceSessionRepository.findByNameAndOwner(uniqueName, owner);
        if (existingWithSuffix != null) {
            return existingWithSuffix;
        }
        RaceSession created = new RaceSession(uniqueName, new ArrayList<>());
        created.setSinglePlayer(true);
        created.setOwner(owner);
        return raceSessionRepository.save(created);
    }
}
