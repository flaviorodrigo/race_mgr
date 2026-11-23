package com.coop.racemgr.controllers;

import com.coop.racemgr.RacemgrApplication;
import com.coop.racemgr.gameserver.GameServerMgr;
import com.coop.racemgr.audit.AuditLog;
import com.coop.racemgr.gameserver.BlackList;
import com.coop.racemgr.gameserver.GameServerProxy;
import com.coop.racemgr.gameserver.ServerConfigFile;
import com.coop.racemgr.gameserver.SessionSettings;
import com.coop.racemgr.gameserver.LiveSession;
import com.coop.racemgr.utils.RacemgrUtils;

import lombok.Data;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
public class GameServerController {
    public enum ServerStatuses {
        start, restart, stop;
    }

    private static final Logger logger = LogManager.getLogger(RacemgrApplication.class);

    private GameServerProxy gameServerProxy = new GameServerProxy();

    @Autowired
    private AuditLog auditLog;

    /** ADMIN ENDPOINTS **/

    @PostMapping("/api/v1/admin/gameserver")
    public ResponseEntity<Object> gameserver(@RequestBody final String request, Authentication authentication) throws InterruptedException, IOException {
        Object requestBodyObj = RacemgrUtils.jsonStrToObj(request);
        var status = RacemgrUtils.getValueFromObj("status", requestBodyObj);
        var gameServerMgr = new GameServerMgr();

        if (GameServerMgr.isExternal()) {
            return ResponseHandler.generateResponse(
                    "The game server is managed outside Race Manager. Start, stop and restart it where it runs.",
                    HttpStatus.CONFLICT, null);
        }

        if (status == null) {
            return ResponseHandler.generateResponse("Missing status parameter!", HttpStatus.BAD_REQUEST, null);
        } else if (status.equals(ServerStatuses.start.toString())) {
            gameServerMgr.start();
        } else if (status.equals(ServerStatuses.restart.toString())) {
            gameServerMgr.restart();
        } else if (status.equals(ServerStatuses.stop.toString())) {
            gameServerMgr.stop();
        } else {
            String msg = "Unknown status parameter!";
            logger.warn(msg);
            return ResponseHandler.generateResponse(msg, HttpStatus.BAD_REQUEST, null);
        }

        auditLog.record(authentication.getName(), "game server " + status, "");
        return ResponseHandler.generateResponse("Server status changed!", HttpStatus.OK, null);
    }

    @Data
    public static class KickRequest {
        private Integer refid;
        // Optional: also ban for this many seconds (the game server's temporary ban)
        private Integer banSeconds;
    }

    @Data
    public static class ChatRequest {
        private String message;
    }

    @PostMapping("/api/v1/admin/gameserver/session/next")
    public ResponseEntity<Object> applyNextSession(@RequestBody Map<String, Integer> settings, Authentication authentication) {
        String problem = SessionSettings.validate(settings);
        if (problem != null) {
            return ResponseHandler.generateResponse(problem, HttpStatus.BAD_REQUEST, null);
        }

        try {
            this.gameServerProxy.setNextAttributes(settings);
            auditLog.record(authentication.getName(), "next session settings", settings.toString());
            return ResponseHandler.generateResponse("Settings sent for the next session", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.warn("Applying settings failed: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @PostMapping("/api/v1/admin/gameserver/session/advance")
    public ResponseEntity<Object> advanceSession(Authentication authentication) {
        try {
            this.gameServerProxy.advanceSession();
            auditLog.record(authentication.getName(), "advance session", "");
            return ResponseHandler.generateResponse("Session advanced", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.warn("Advance failed: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @Data
    public static class BanListRequest {
        private String steamId;
        private String note;
    }

    @GetMapping("/api/v1/admin/gameserver/banlist")
    public ResponseEntity<Object> banList() {
        try {
            return ResponseHandler.generateResponse("Ban list", HttpStatus.OK, BlackList.forServer().list());
        } catch (BlackList.UnsupportedFormatException e) {
            return ResponseHandler.generateResponse(e.getMessage(), HttpStatus.CONFLICT, null);
        } catch (Exception e) {
            logger.warn("Reading the ban list failed: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @PostMapping("/api/v1/admin/gameserver/banlist")
    public ResponseEntity<Object> banListAdd(@RequestBody BanListRequest request, Authentication authentication) {
        if (!BlackList.isSteamId(request.getSteamId())) {
            return ResponseHandler.generateResponse("A Steam ID is 17 digits", HttpStatus.BAD_REQUEST, null);
        }

        try {
            BlackList.forServer().add(request.getSteamId(), request.getNote());
            auditLog.record(authentication.getName(), "ban list add", request.getSteamId());
            return ResponseHandler.generateResponse("Added to the ban list. The game server reads it when it next loads its configuration.", HttpStatus.OK, null);
        } catch (BlackList.UnsupportedFormatException e) {
            return ResponseHandler.generateResponse(e.getMessage(), HttpStatus.CONFLICT, null);
        } catch (Exception e) {
            logger.warn("Updating the ban list failed: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @DeleteMapping("/api/v1/admin/gameserver/banlist/{steamId}")
    public ResponseEntity<Object> banListRemove(@PathVariable String steamId, Authentication authentication) {
        if (!BlackList.isSteamId(steamId)) {
            return ResponseHandler.generateResponse("A Steam ID is 17 digits", HttpStatus.BAD_REQUEST, null);
        }

        try {
            boolean removed = BlackList.forServer().remove(steamId);
            if (removed) {
                auditLog.record(authentication.getName(), "ban list remove", steamId);
            }
            return ResponseHandler.generateResponse(removed ? "Removed from the ban list" : "Not on the ban list",
                    removed ? HttpStatus.OK : HttpStatus.NOT_FOUND, null);
        } catch (BlackList.UnsupportedFormatException e) {
            return ResponseHandler.generateResponse(e.getMessage(), HttpStatus.CONFLICT, null);
        } catch (Exception e) {
            logger.warn("Updating the ban list failed: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @GetMapping("/api/v1/admin/gameserver/config")
    public ResponseEntity<Object> serverConfig() {
        try {
            return ResponseHandler.generateResponse("Server settings", HttpStatus.OK, ServerConfigFile.forServer().read());
        } catch (Exception e) {
            logger.warn("Reading server.cfg failed: " + e);
            return ResponseHandler.generateResponse("Could not read server.cfg", HttpStatus.MULTI_STATUS, null);
        }
    }

    // Changes a few well-known values in server.cfg (nothing else in the file is touched). The game server
    // reads the file when it starts, so a restart applies the changes.
    @PutMapping("/api/v1/admin/gameserver/config")
    public ResponseEntity<Object> updateServerConfig(@RequestBody Map<String, Object> changes, Authentication authentication) {
        String problem = ServerConfigFile.validate(changes);
        if (problem != null) {
            return ResponseHandler.generateResponse(problem, HttpStatus.BAD_REQUEST, null);
        }

        try {
            ServerConfigFile.forServer().write(changes);
            // The password itself never goes into the log
            auditLog.record(authentication.getName(), "server.cfg changed", String.join(", ", changes.keySet()));
            return ResponseHandler.generateResponse("Saved. Restart the game server for the changes to apply.", HttpStatus.OK, null);
        } catch (java.io.IOException e) {
            logger.warn("Writing server.cfg failed: " + e);
            return ResponseHandler.generateResponse("Could not change server.cfg", HttpStatus.MULTI_STATUS, null);
        }
    }

    // Live view for admins: includes the reference id needed to kick and the Steam id
    @GetMapping("/api/v1/admin/gameserver/session")
    public ResponseEntity<Object> adminSession() {
        try {
            var summary = LiveSession.summarize(this.gameServerProxy.getSessionStatus(), true);
            return ResponseHandler.generateResponse("Live session", HttpStatus.OK, summary);
        } catch (Exception e) {
            logger.warn("Live session unavailable: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @PostMapping("/api/v1/admin/gameserver/kick")
    public ResponseEntity<Object> kick(@RequestBody KickRequest request, Authentication authentication) {
        if (!LiveSession.isValidRefId(request.getRefid())) {
            return ResponseHandler.generateResponse("A valid player reference id is required", HttpStatus.BAD_REQUEST, null);
        }

        if (!LiveSession.isValidBan(request.getBanSeconds())) {
            return ResponseHandler.generateResponse("A ban must be between 1 second and one year", HttpStatus.BAD_REQUEST, null);
        }

        try {
            this.gameServerProxy.kick(request.getRefid(), request.getBanSeconds());
            auditLog.record(authentication.getName(), request.getBanSeconds() == null ? "kick" : "kick and ban",
                    "player refid " + request.getRefid() + (request.getBanSeconds() == null ? "" : " for " + request.getBanSeconds() + "s"));
            return ResponseHandler.generateResponse(request.getBanSeconds() == null ? "Player kicked" : "Player kicked and banned", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.warn("Kick failed: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @PostMapping("/api/v1/admin/gameserver/chat")
    public ResponseEntity<Object> chat(@RequestBody ChatRequest request, Authentication authentication) {
        String message = LiveSession.cleanChatMessage(request.getMessage());
        if (message == null) {
            return ResponseHandler.generateResponse("A message of 1 to " + LiveSession.MAX_CHAT_LENGTH + " characters is required",
                    HttpStatus.BAD_REQUEST, null);
        }

        try {
            this.gameServerProxy.sendChat(message);
            auditLog.record(authentication.getName(), "chat message", message);
            return ResponseHandler.generateResponse("Message sent", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.warn("Chat failed: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    /** PUBLIC ENDPOINTS **/

    // Who is online right now. Names and ping only: no reference or Steam ids.
    @GetMapping("/api/v1/gameserver/session")
    public ResponseEntity<Object> session() {
        try {
            var summary = LiveSession.summarize(this.gameServerProxy.getSessionStatus(), false);
            return ResponseHandler.generateResponse("Live session", HttpStatus.OK, summary);
        } catch (Exception e) {
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @GetMapping("/api/v1/gameserver")
    public ResponseEntity<Object> gameserver() throws InterruptedException, IOException {
        Map<String, Object> response = Map.of("isRunning", GameServerMgr.isRunning(), "managed", !GameServerMgr.isExternal());
        return ResponseHandler.generateResponse("Current external game server status", HttpStatus.OK, response);
    }

    @GetMapping("/api/v1/gameserver/weather")
    public ResponseEntity<Object> weather() {
        try {
            List weather = this.gameServerProxy.getWeather();
            return ResponseHandler.generateResponse("List of weather options", HttpStatus.OK, weather);
        }  catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @GetMapping("/api/v1/gameserver/tracks")
    public ResponseEntity<Object> tracks() {
        try {
            List tracks = this.gameServerProxy.getTracks();
            return ResponseHandler.generateResponse("List of track options", HttpStatus.OK, tracks);
        }  catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @GetMapping("/api/v1/gameserver/liveries")
    public ResponseEntity<Object> liveries() {
        try {
            List liveries = this.gameServerProxy.getLiveries();
            return ResponseHandler.generateResponse("List of livery options", HttpStatus.OK, liveries);
        }  catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @GetMapping("/api/v1/gameserver/vehicles")
    public ResponseEntity<Object> vehicles() {
        try {
            List vehicles = this.gameServerProxy.getVehicles();
            return ResponseHandler.generateResponse("List of vehicle options", HttpStatus.OK, vehicles);
        }  catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @GetMapping("/api/v1/gameserver/vehicle_classes")
    public ResponseEntity<Object> vehicleClasses() {
        try {
            List vehicleClasses = this.gameServerProxy.getVehicleClasses();
            return ResponseHandler.generateResponse("List of vehicle class options", HttpStatus.OK, vehicleClasses);
        }  catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }
}
