package com.coop.racemgr.controllers;

import com.coop.racemgr.RacemgrApplication;
import com.coop.racemgr.audit.AuditLog;
import com.coop.racemgr.gameserver.GameServerProxy;
import com.coop.racemgr.gameserver.SessionSettings;
import com.coop.racemgr.model.SessionPreset;
import com.coop.racemgr.repositories.SessionPresetRepository;
import lombok.Data;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Saved next-session setups: store a named set of settings, apply it to the game server's next session later. */
@RestController
public class SessionPresetController {
    private static final Logger logger = LogManager.getLogger(RacemgrApplication.class);
    private static final String ID_PATTERN = "^[0-9a-f]{24}$";

    @Autowired
    private SessionPresetRepository presetRepository;

    @Autowired
    private AuditLog auditLog;

    private final GameServerProxy gameServerProxy = new GameServerProxy();

    @Data
    public static class PresetRequest {
        private String name;
        private Map<String, Integer> settings;
    }

    @GetMapping("/api/v1/admin/gameserver/presets")
    public ResponseEntity<Object> list() {
        try {
            return ResponseHandler.generateResponse("Presets", HttpStatus.OK, presetRepository.findAllByOrderByNameAsc());
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    // A preset with the same name is replaced
    @PostMapping("/api/v1/admin/gameserver/presets")
    public ResponseEntity<Object> save(@RequestBody PresetRequest request, Authentication authentication) {
        String name = SessionPreset.cleanName(request.getName());
        if (name == null) {
            return ResponseHandler.generateResponse("A name of 1 to " + SessionPreset.MAX_NAME_LENGTH + " characters is required", HttpStatus.BAD_REQUEST, null);
        }

        String problem = SessionSettings.validate(request.getSettings());
        if (problem != null) {
            return ResponseHandler.generateResponse(problem, HttpStatus.BAD_REQUEST, null);
        }

        try {
            SessionPreset preset = presetRepository.findByName(name);
            if (preset == null) {
                if (presetRepository.count() >= SessionPreset.MAX_PRESETS) {
                    return ResponseHandler.generateResponse("At most " + SessionPreset.MAX_PRESETS + " presets: delete one first", HttpStatus.BAD_REQUEST, null);
                }
                preset = new SessionPreset();
                preset.setName(name);
            }
            preset.setSettings(new LinkedHashMap<>(request.getSettings()));
            presetRepository.save(preset);
            auditLog.record(authentication.getName(), "preset saved", name);
            return ResponseHandler.generateResponse("Preset saved", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @DeleteMapping("/api/v1/admin/gameserver/presets/{id}")
    public ResponseEntity<Object> delete(@PathVariable String id, Authentication authentication) {
        if (!id.matches(ID_PATTERN)) {
            return ResponseHandler.generateResponse("Preset not found", HttpStatus.NOT_FOUND, null);
        }

        try {
            var preset = presetRepository.findById(id);
            if (preset.isEmpty()) {
                return ResponseHandler.generateResponse("Preset not found", HttpStatus.NOT_FOUND, null);
            }
            presetRepository.deleteById(id);
            auditLog.record(authentication.getName(), "preset deleted", preset.get().getName());
            return ResponseHandler.generateResponse("Preset deleted", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    // Sends the preset's settings for the next session. The stored values are checked again: the database is not trusted.
    @PostMapping("/api/v1/admin/gameserver/presets/{id}/apply")
    public ResponseEntity<Object> apply(@PathVariable String id, Authentication authentication) {
        if (!id.matches(ID_PATTERN)) {
            return ResponseHandler.generateResponse("Preset not found", HttpStatus.NOT_FOUND, null);
        }

        try {
            var found = presetRepository.findById(id);
            if (found.isEmpty()) {
                return ResponseHandler.generateResponse("Preset not found", HttpStatus.NOT_FOUND, null);
            }

            SessionPreset preset = found.get();
            if (SessionSettings.validate(preset.getSettings()) != null) {
                return ResponseHandler.generateResponse("This preset has settings that are not allowed", HttpStatus.CONFLICT, null);
            }

            gameServerProxy.setNextAttributes(preset.getSettings());
            auditLog.record(authentication.getName(), "preset applied", preset.getName());
            return ResponseHandler.generateResponse("Preset sent for the next session", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.warn("Applying a preset failed: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }
}
