package com.coop.racemgr.controllers;

import com.coop.racemgr.RacemgrApplication;
import com.coop.racemgr.gameserver.GameServerMgr;
import com.coop.racemgr.gameserver.GameServerProxy;
import com.coop.racemgr.gameserver.LiveSession;
import com.coop.racemgr.utils.RacemgrUtils;

import lombok.Data;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

    /** ADMIN ENDPOINTS **/

    @PostMapping("/api/v1/admin/gameserver")
    public ResponseEntity<Object> gameserver(@RequestBody final String request) throws InterruptedException, IOException {
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

        return ResponseHandler.generateResponse("Server status changed!", HttpStatus.OK, null);
    }

    @Data
    public static class KickRequest {
        private Integer refid;
    }

    @Data
    public static class ChatRequest {
        private String message;
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

        try {
            this.gameServerProxy.kick(request.getRefid());
            logger.info("Admin " + authentication.getName() + " kicked player refid " + request.getRefid());
            return ResponseHandler.generateResponse("Player kicked", HttpStatus.OK, null);
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
            logger.info("Admin " + authentication.getName() + " sent a chat message");
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
