package com.coop.racemgr.controllers;

import com.coop.racemgr.RacemgrApplication;
import com.coop.racemgr.audit.AuditLog;
import com.coop.racemgr.gameserver.GameServerMgr;
import com.coop.racemgr.gameserver.GameServerProxy;
import com.coop.racemgr.gameserver.RotationAddon;
import com.coop.racemgr.model.RaceRotation;
import com.coop.racemgr.repositories.RaceRotationRepository;
import com.coop.racemgr.rotation.RacemgrRotationConfigMaker;
import com.coop.racemgr.rotation.RacemgrRotationFileMaker;
import com.coop.racemgr.rotation.RotationValidator;

import com.coop.racemgr.utils.RacemgrUtils;
import lombok.Data;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

// @TODO:
//  1) Support expanded custom rotations and not just semi-random

@RestController
public class RaceRotationController {
    @Autowired
    private RaceRotationRepository raceRotationRepository;

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private com.coop.racemgr.repositories.RaceMgrConfigRepository raceMgrConfigRepository;

    private boolean isActiveRotation(String id) {
        var config = raceMgrConfigRepository.findItemById("1");
        return config != null && id.equals(config.getActiveRaceRotationId());
    }

    @Data
    public static class RaceRotationCustomRequest {
        private String name;
        private Object config;
        private Long updated;
        private Boolean active = true;
        // Write the rotation to the game server's rotation file right away (the original API behaviour).
        // The UI's builder sets this to false and leaves activating it to the usual Update Server step.
        private Boolean apply = true;
    }

    @Data
    public static class RaceRotationGenerationRequest {
        private String name = String.valueOf(UUID.randomUUID());
        private Integer raceCount = 5;
        private Boolean allowKarts = false;
        private Boolean persist = true;
        private Boolean restart = false;
        private Long updated;
        private Boolean active = true;
    }

    private static final int MAX_PAGE_SIZE = 1000;

    private static final Logger logger = LogManager.getLogger(RacemgrApplication.class);

    /** ADMIN ENDPOINTS **/

    // @FIXME: This is a quick way to add support for custom rotations. For now there is no additional validation of given rotation.
    // It assumes the same limited file format as what is created by the generator. Examples included in the Postman collection...
    @PostMapping("/api/v1/admin/race/rotation")
    public ResponseEntity<Object> rotation(@RequestBody RaceRotationCustomRequest request, Authentication authentication) {
        if (request.name == null || request.name.isBlank() || request.name.length() > 64) {
            return ResponseHandler.generateResponse("A rotation name of up to 64 characters is required", HttpStatus.BAD_REQUEST, null);
        }
        String problem = RotationValidator.validate(request.config);
        if (problem != null) {
            return ResponseHandler.generateResponse(problem, HttpStatus.BAD_REQUEST, null);
        }

        RaceRotation raceRotation;
        try {
            raceRotation = new RaceRotation(request.name, request.config);
            if (!Boolean.FALSE.equals(request.apply)) {
                var rotationFileMaker = new RacemgrRotationFileMaker(raceRotation);
                rotationFileMaker.writeGeneratedRotationFile();
            }

            // writeGeneratedRotationFile() also removes the game server's cached rotation; the
            // server must be restarted for the new rotation to take effect.
            raceRotationRepository.save(raceRotation);
            auditLog.record(authentication.getName(), "custom rotation saved", request.name);
            return ResponseHandler.generateResponse("Successfully saved custom rotation", HttpStatus.OK, raceRotation);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @PostMapping("/api/v1/admin/race/rotation/generate")
    public ResponseEntity<Object> rotationGeneration(@RequestBody RaceRotationGenerationRequest request, Authentication authentication) {
        // Generate rotation config
        RacemgrRotationConfigMaker rotationConfig = new RacemgrRotationConfigMaker(new GameServerProxy(), request.raceCount, request.allowKarts, request.persist);

        try {
            var raceRotation = new RaceRotation(request.name, rotationConfig);
            raceRotationRepository.save(raceRotation);
            auditLog.record(authentication.getName(), "rotation generated", request.name);
            return ResponseHandler.generateResponse("Successfully generated new rotation", HttpStatus.OK, raceRotation);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    // Changes a saved rotation in place: its name and its setup. Races recorded under it stay under it.
    @PutMapping("/api/v1/admin/race/rotation/{id}")
    public ResponseEntity<Object> updateRotation(@PathVariable String id, @RequestBody RaceRotationCustomRequest request, Authentication authentication) {
        if (request.name == null || request.name.isBlank() || request.name.length() > 64) {
            return ResponseHandler.generateResponse("A rotation name of up to 64 characters is required", HttpStatus.BAD_REQUEST, null);
        }
        String problem = RotationValidator.validate(request.config);
        if (problem != null) {
            return ResponseHandler.generateResponse(problem, HttpStatus.BAD_REQUEST, null);
        }

        try {
            var rr = raceRotationRepository.findItemById(id);
            if (rr == null || Boolean.FALSE.equals(rr.getActive())) {
                return ResponseHandler.generateResponse("Race rotation not found!", HttpStatus.NOT_FOUND, null);
            }
            rr.setName(request.name);
            rr.setConfig(request.config);
            rr.setUpdated(System.currentTimeMillis());
            raceRotationRepository.save(rr);
            auditLog.record(authentication.getName(), "rotation changed", rr.getName());
            return ResponseHandler.generateResponse(isActiveRotation(id)
                    ? "Saved. It is the active rotation: press Update Server to send the new version to the game server."
                    : "Saved.", HttpStatus.OK, rr);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @DeleteMapping("/api/v1/admin/race/rotation/{id}")
    public ResponseEntity<Object> deleteRaceRotation(@PathVariable String id, Authentication authentication) {
        var rr = raceRotationRepository.findItemById(id);
        if (rr == null) {
            return ResponseHandler.generateResponse("Race rotation not found!", HttpStatus.NOT_FOUND, null);
        }
        // Races are being recorded under it: choose another active rotation first
        if (isActiveRotation(id)) {
            return ResponseHandler.generateResponse("This is the active rotation. Set another one active first.", HttpStatus.CONFLICT, null);
        }

        try {
            rr.setActive(false);
            rr.setUpdated(System.currentTimeMillis());
            raceRotationRepository.save(rr);
            auditLog.record(authentication.getName(), "rotation deleted", rr.getName());
            return ResponseHandler.generateResponse("Successfully deleted race rotation!", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @PostMapping("/api/v1/admin/race/rotation/{id}/activate")
    public ResponseEntity<Object> activateRaceRotation(@PathVariable String id, Authentication authentication) {
        RaceRotation rr = raceRotationRepository.findItemById(id);
        if (rr == null) {
            return ResponseHandler.generateResponse("Race rotation not found!", HttpStatus.NOT_FOUND, null);
        }

        try {
            var rotationFileMaker = new RacemgrRotationFileMaker(rr);
            rotationFileMaker.writeGeneratedRotationFile();
            auditLog.record(authentication.getName(), "rotation activated", rr.getName());
            if (GameServerMgr.isExternal()) {
                // With the racemgr_rotate addon in use, the running server picks the rotation up by itself
                var addon = RotationAddon.forServer();
                if (addon.isInstalled()) {
                    long version = addon.writeRotation(rr.config);
                    if (Boolean.TRUE.equals(addon.status().get("ready"))) {
                        return ResponseHandler.generateResponse(
                                "Rotation sent to the game server. It takes effect within a few seconds: no restart, and nobody has to leave.",
                                HttpStatus.OK, Map.of("version", version, "restartNeeded", false));
                    }
                }
                return ResponseHandler.generateResponse(
                        "Rotation written. Restart the game server to apply it (it is managed outside Race Manager).", HttpStatus.OK,
                        Map.of("restartNeeded", true));
            }

            GameServerMgr.restart();
            return ResponseHandler.generateResponse("Successfully activated race rotation, restarting server now to apply!", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    // The racemgr_rotate addon: whether it is installed and in use, and what it reports
    @GetMapping("/api/v1/admin/gameserver/rotation-addon")
    public ResponseEntity<Object> rotationAddonStatus() {
        try {
            return ResponseHandler.generateResponse("Rotation addon", HttpStatus.OK, RotationAddon.forServer().status());
        } catch (Exception e) {
            logger.warn("Reading the rotation addon status failed: " + e);
            return ResponseHandler.generateResponse("Could not read the game server's folder", HttpStatus.MULTI_STATUS, null);
        }
    }

    // Copies the addon into the game server's folder and hands it the active rotation. Using it still takes one
    // restart, to load it: server.cfg's luaApiAddons then lists racemgr_rotate where it listed sms_rotate.
    @PostMapping("/api/v1/admin/gameserver/rotation-addon/install")
    public ResponseEntity<Object> installRotationAddon(Authentication authentication) {
        try {
            var addon = RotationAddon.forServer();
            addon.install();
            var config = raceMgrConfigRepository.findItemById("1");
            if (config != null && config.getActiveRaceRotationId() != null && !config.getActiveRaceRotationId().isBlank()) {
                var active = raceRotationRepository.findItemById(config.getActiveRaceRotationId());
                if (active != null) {
                    addon.writeRotation(active.config);
                }
            }
            auditLog.record(authentication.getName(), "rotation addon installed", "");
            return ResponseHandler.generateResponse("Installed. In server.cfg, put \"racemgr_rotate\" in luaApiAddons instead of \"sms_rotate\", then restart the game server once.",
                    HttpStatus.OK, addon.status());
        } catch (Exception e) {
            logger.warn("Installing the rotation addon failed: " + e);
            return ResponseHandler.generateResponse("Could not install the addon in the game server's folder", HttpStatus.MULTI_STATUS, null);
        }
    }

    // Starts the active rotation again from round 1 without restarting the game server: the racemgr_rotate addon
    // queues round 1 for the next session. Nobody is disconnected; an open session keeps its settings.
    @PostMapping("/api/v1/admin/gameserver/rotation-addon/start-over")
    public ResponseEntity<Object> startRotationOver(Authentication authentication) {
        try {
            var addon = RotationAddon.forServer();
            if (!Boolean.TRUE.equals(addon.status().get("ready"))) {
                return ResponseHandler.generateResponse("This needs the racemgr_rotate addon in use (see Server, Rotation addon). Without it, use Restart game server and start the rotation from its first round.",
                        HttpStatus.CONFLICT, null);
            }
            var config = raceMgrConfigRepository.findItemById("1");
            var active = config == null || config.getActiveRaceRotationId() == null ? null : raceRotationRepository.findItemById(config.getActiveRaceRotationId());
            if (active == null) {
                return ResponseHandler.generateResponse("No active rotation. Set one active on the Championships tab first.", HttpStatus.CONFLICT, null);
            }
            long version = addon.writeRotation(active.config, true);
            auditLog.record(authentication.getName(), "rotation started from round 1", active.getName());
            return ResponseHandler.generateResponse("The rotation starts again from round 1 with the next session. Nobody is disconnected; a session that is open keeps its settings.",
                    HttpStatus.OK, Map.of("version", version));
        } catch (Exception e) {
            logger.warn("Starting the rotation over failed: " + e);
            return ResponseHandler.generateResponse("Could not hand the rotation to the game server", HttpStatus.MULTI_STATUS, null);
        }
    }

    /** PUBLIC ENDPOINTS **/

    @GetMapping("/api/v1/race/rotation/{id}")
    public ResponseEntity<Object> raceRotationDetails(@PathVariable String id) {
        var rr = raceRotationRepository.findItemById(id);
        if (rr == null) {
            return ResponseHandler.generateResponse("Race session not found!", HttpStatus.NOT_FOUND, null);
        }  else {
            return ResponseHandler.generateResponse("Race session details", HttpStatus.OK, rr);
        }
    }

    @GetMapping("/api/v1/race/rotation")
    public ResponseEntity<Object> raceRotationList(@RequestParam(defaultValue = "0") Integer page, @RequestParam(defaultValue = "10") Integer limit) {
        Pageable paging = RacemgrUtils.boundedPageRequest(page, limit, MAX_PAGE_SIZE);
        try {
            var raceRotations = raceRotationRepository.findAll(paging);
            return ResponseHandler.generateResponse("Race rotation list", HttpStatus.OK, PageResponse.of(raceRotations));
        }  catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }
}