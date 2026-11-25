package com.coop.racemgr.controllers;

import com.coop.racemgr.RacemgrApplication;
import com.coop.racemgr.gameserver.GameServerMgr;
import com.coop.racemgr.audit.AuditLog;
import com.coop.racemgr.gameserver.SteamIdList;
import com.coop.racemgr.gameserver.GameServerProxy;
import com.coop.racemgr.gameserver.PerformanceFiles;
import com.coop.racemgr.gameserver.ServerConfigFile;
import com.coop.racemgr.gameserver.SessionSettings;
import com.coop.racemgr.gameserver.GameServerLog;
import com.coop.racemgr.gameserver.LiveSession;
import com.coop.racemgr.gameserver.LiveTiming;
import com.coop.racemgr.gameserver.RestartSignal;
import com.coop.racemgr.stats.AiPilotConverter;
import com.coop.racemgr.stats.GameServerLookup;
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

    @Autowired
    private GameServerLookup gameServerLookup;

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
        return listEntries(SteamIdList::forServer, "Ban list");
    }

    @PostMapping("/api/v1/admin/gameserver/banlist")
    public ResponseEntity<Object> banListAdd(@RequestBody BanListRequest request, Authentication authentication) {
        return addEntry(SteamIdList::forServer, request, authentication, "ban list add", "Added to the ban list. The game server reads it when it next loads its configuration.");
    }

    @DeleteMapping("/api/v1/admin/gameserver/banlist/{steamId}")
    public ResponseEntity<Object> banListRemove(@PathVariable String steamId, Authentication authentication) {
        return removeEntry(SteamIdList::forServer, steamId, authentication, "ban list remove", "the ban list");
    }

    // The whitelist: while server.cfg references it, only these players can join
    @GetMapping("/api/v1/admin/gameserver/whitelist")
    public ResponseEntity<Object> whitelist() {
        try {
            var entries = SteamIdList.whitelistForServer().list();
            return ResponseHandler.generateResponse("Whitelist", HttpStatus.OK,
                    Map.of("entries", entries, "active", SteamIdList.whitelistReferenced()));
        } catch (SteamIdList.UnsupportedFormatException e) {
            return ResponseHandler.generateResponse(e.getMessage(), HttpStatus.CONFLICT, null);
        } catch (Exception e) {
            logger.warn("Reading the whitelist failed: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @PostMapping("/api/v1/admin/gameserver/whitelist")
    public ResponseEntity<Object> whitelistAdd(@RequestBody BanListRequest request, Authentication authentication) {
        return addEntry(SteamIdList::whitelistForServer, request, authentication, "whitelist add", "Added to the whitelist. The game server reads it when it next loads its configuration.");
    }

    @DeleteMapping("/api/v1/admin/gameserver/whitelist/{steamId}")
    public ResponseEntity<Object> whitelistRemove(@PathVariable String steamId, Authentication authentication) {
        return removeEntry(SteamIdList::whitelistForServer, steamId, authentication, "whitelist remove", "the whitelist");
    }

    private ResponseEntity<Object> listEntries(java.util.function.Supplier<SteamIdList> list, String title) {
        try {
            return ResponseHandler.generateResponse(title, HttpStatus.OK, list.get().list());
        } catch (SteamIdList.UnsupportedFormatException e) {
            return ResponseHandler.generateResponse(e.getMessage(), HttpStatus.CONFLICT, null);
        } catch (Exception e) {
            logger.warn("Reading the " + title.toLowerCase() + " failed: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    private ResponseEntity<Object> addEntry(java.util.function.Supplier<SteamIdList> list, BanListRequest request, Authentication authentication, String action, String okMessage) {
        if (!SteamIdList.isSteamId(request.getSteamId())) {
            return ResponseHandler.generateResponse("A Steam ID is 17 digits", HttpStatus.BAD_REQUEST, null);
        }

        try {
            list.get().add(request.getSteamId(), request.getNote());
            auditLog.record(authentication.getName(), action, request.getSteamId());
            return ResponseHandler.generateResponse(okMessage, HttpStatus.OK, null);
        } catch (SteamIdList.UnsupportedFormatException e) {
            return ResponseHandler.generateResponse(e.getMessage(), HttpStatus.CONFLICT, null);
        } catch (Exception e) {
            logger.warn("Updating the list failed: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    private ResponseEntity<Object> removeEntry(java.util.function.Supplier<SteamIdList> list, String steamId, Authentication authentication, String action, String listName) {
        if (!SteamIdList.isListedId(steamId)) {
            return ResponseHandler.generateResponse("A Steam ID is 17 digits", HttpStatus.BAD_REQUEST, null);
        }

        try {
            boolean removed = list.get().remove(steamId);
            if (removed) {
                auditLog.record(authentication.getName(), action, steamId);
            }
            return ResponseHandler.generateResponse(removed ? "Removed from " + listName : "Not on " + listName,
                    removed ? HttpStatus.OK : HttpStatus.NOT_FOUND, null);
        } catch (SteamIdList.UnsupportedFormatException e) {
            return ResponseHandler.generateResponse(e.getMessage(), HttpStatus.CONFLICT, null);
        } catch (Exception e) {
            logger.warn("Updating the list failed: " + e);
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

    @Data
    public static class PerformanceTable {
        private Boolean enabled;
        private Double defaultValue;
        private java.util.Map<String, Double> drivers;
    }

    @Data
    public static class PerformanceRequest {
        private PerformanceTable ballast;
        private PerformanceTable power;
        private PerformanceTable drag;
    }

    // Balance of performance: per-driver ballast, power and drag, from the game server's three small JSON files
    @GetMapping("/api/v1/admin/gameserver/performance")
    public ResponseEntity<Object> performance() {
        try {
            var tables = PerformanceFiles.forServer().read();
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            tables.forEach((kind, t) -> body.put(kind.name().toLowerCase(), Map.of(
                    "enabled", t.enabled(), "defaultValue", t.defaultValue(), "drivers", t.drivers(), "file", t.file())));
            return ResponseHandler.generateResponse("Balance of performance", HttpStatus.OK, body);
        } catch (Exception e) {
            logger.warn("Reading the performance files failed: " + e);
            return ResponseHandler.generateResponse(e instanceof java.io.IOException ? e.getMessage() : "Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    // The game server reads these files when it loads its configuration: restart it to apply changes
    @PutMapping("/api/v1/admin/gameserver/performance")
    public ResponseEntity<Object> updatePerformance(@RequestBody PerformanceRequest request, Authentication authentication) {
        Map<PerformanceFiles.Kind, PerformanceFiles.Table> tables = new java.util.LinkedHashMap<>();
        add(tables, PerformanceFiles.Kind.BALLAST, request.getBallast());
        add(tables, PerformanceFiles.Kind.POWER, request.getPower());
        add(tables, PerformanceFiles.Kind.DRAG, request.getDrag());

        String problem = PerformanceFiles.validate(tables);
        if (problem != null) {
            return ResponseHandler.generateResponse(problem, HttpStatus.BAD_REQUEST, null);
        }

        try {
            PerformanceFiles.forServer().write(tables);
            auditLog.record(authentication.getName(), "balance of performance changed",
                    tables.keySet().stream().map(k -> k.name().toLowerCase()).collect(java.util.stream.Collectors.joining(", ")));
            return ResponseHandler.generateResponse("Saved. Restart the game server for the changes to apply.", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.warn("Writing the performance files failed: " + e);
            return ResponseHandler.generateResponse(e instanceof java.io.IOException ? e.getMessage() : "Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    private static void add(Map<PerformanceFiles.Kind, PerformanceFiles.Table> tables, PerformanceFiles.Kind kind, PerformanceTable table) {
        if (table != null) {
            tables.put(kind, new PerformanceFiles.Table(Boolean.TRUE.equals(table.getEnabled()),
                    table.getDefaultValue() == null ? Double.NaN : table.getDefaultValue(),
                    table.getDrivers() == null ? null : new java.util.LinkedHashMap<>(table.getDrivers()), ""));
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

    // Everything a session can set, as the game server describes it (for the rotation builder)
    @GetMapping("/api/v1/admin/gameserver/schema")
    public ResponseEntity<Object> sessionSchema() {
        try {
            return ResponseHandler.generateResponse("Session settings", HttpStatus.OK, this.gameServerProxy.getSessionSchema());
        } catch (Exception e) {
            logger.warn("Session settings unavailable: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    // The game server's own event log, newest first
    @GetMapping("/api/v1/admin/gameserver/log")
    public ResponseEntity<Object> log(@RequestParam(required = false) Integer count) {
        try {
            var events = GameServerLog.latestFirst(this.gameServerProxy.getLog(GameServerLog.clampCount(count)));
            return ResponseHandler.generateResponse("Game server log", HttpStatus.OK, events);
        } catch (Exception e) {
            logger.warn("Game server log unavailable: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    @Data
    public static class RestartRequest {
        private Boolean resetRotation;
    }

    // Whether a requested restart is still waiting for the game server's entrypoint to pick it up
    @GetMapping("/api/v1/admin/gameserver/restart")
    public ResponseEntity<Object> restartStatus() {
        try {
            return ResponseHandler.generateResponse("Restart", HttpStatus.OK,
                    Map.of("enabled", RestartSignal.isEnabled(), "pending", RestartSignal.isEnabled() && RestartSignal.isPending()));
        } catch (Exception e) {
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    // Restarts a game server that runs in its own container, through the restart signal (see RestartSignal)
    @PostMapping("/api/v1/admin/gameserver/restart")
    public ResponseEntity<Object> restartExternal(@RequestBody(required = false) RestartRequest request, Authentication authentication) {
        if (!GameServerMgr.isExternal()) {
            return ResponseHandler.generateResponse("Race Manager runs this game server itself: use Restart Server", HttpStatus.CONFLICT, null);
        }
        if (!RestartSignal.isEnabled()) {
            return ResponseHandler.generateResponse("Restarting the game server from here isn't set up: see the README (RM_GAMESERVER_RESTART)", HttpStatus.CONFLICT, null);
        }

        boolean reset = request != null && Boolean.TRUE.equals(request.getResetRotation());
        try {
            if (RestartSignal.isPending()) {
                return ResponseHandler.generateResponse("A restart is already on its way", HttpStatus.CONFLICT, null);
            }
            RestartSignal.request(reset);
            auditLog.record(authentication.getName(), "game server restart", reset ? "rotation from its first round" : "");
            return ResponseHandler.generateResponse("Restart requested. The game server is back in about half a minute.", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.warn("Restart request failed: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

    /** PUBLIC ENDPOINTS **/

    // Live timing: the cars in race order with lap and sector times. Names only: no reference or Steam ids.
    @GetMapping("/api/v1/gameserver/timing")
    public ResponseEntity<Object> timing() {
        try {
            var board = LiveTiming.summarize(this.gameServerProxy.getSessionStatus());
            return ResponseHandler.generateResponse("Live timing", HttpStatus.OK, AiPilotConverter.convertLiveBoard(board, gameServerLookup));
        } catch (Exception e) {
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }

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
        Map<String, Object> response = Map.of("isRunning", GameServerMgr.isRunning(), "managed", !GameServerMgr.isExternal(),
                "restartable", !GameServerMgr.isExternal() || RestartSignal.isEnabled());
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
