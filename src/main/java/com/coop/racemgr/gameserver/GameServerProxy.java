package com.coop.racemgr.gameserver;

import com.coop.racemgr.utils.RacemgrUtils;
import org.apache.commons.io.FileUtils;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Component
public class GameServerProxy {
    final private String gameServerHost = GameServerSettings.get("RM_GAMESERVER_HOST"); // e.g. localhost
    final private String gameServerPort = GameServerSettings.get("RM_GAMESERVER_PORT");

    // e.g. http://localhost:9000/api
    final private String gameServerBaseUrl = "http://" + gameServerHost + ":" + gameServerPort + "/api";
    // All the interesting game data generally hangs off the /lists endpoint
    final private String gameServerListBaseUrl = gameServerBaseUrl + "/list";

    // Bounded timeouts: this is called from public endpoints, so a hung game server must not pile up threads
    private static final RestClient REST_CLIENT = RestClient.builder()
            .requestFactory(timeouts())
            // Optional HTTP basic auth for a game server whose API isn't open to Race Manager's address.
            // Read per request. NOTE: the game server speaks plain HTTP, so keep this on a trusted network.
            .defaultRequest(spec -> {
                String user = GameServerSettings.get("RM_GAMESERVER_USER");
                String password = GameServerSettings.get("RM_GAMESERVER_PASSWORD");
                if (user != null && !user.isBlank() && password != null) {
                    spec.headers(h -> h.setBasicAuth(user, password));
                }
            })
            .build();

    private static JdkClientHttpRequestFactory timeouts() {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        // The game server (libwebsockets) hangs up on the HTTP/2 upgrade request the JDK client sends
                        // by default over plain HTTP, so speak HTTP/1.1 only.
                        .version(HttpClient.Version.HTTP_1_1)
                        .build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }

    // The status is polled by every open browser, so share one response for a moment
    private static final long STATUS_CACHE_MILLIS = 2000;
    private static volatile JsonNode cachedStatus;
    private static volatile long cachedStatusAt;

    /** The live session: attributes, members (connected players) and participants (cars). */
    public JsonNode getSessionStatus() {
        long now = System.currentTimeMillis();
        JsonNode cached = cachedStatus;
        if (cached != null && now - cachedStatusAt < STATUS_CACHE_MILLIS) {
            return cached;
        }

        String body = REST_CLIENT.get()
                .uri(gameServerBaseUrl + "/session/status?attributes&members&participants")
                .retrieve()
                .body(String.class);
        JsonNode response;
        try {
            response = RacemgrUtils.JSON.readTree(body).path("response");
        } catch (JacksonException e) {
            throw new IllegalStateException("Unreadable response from the game server", e);
        }
        if (!response.isObject()) {
            throw new IllegalStateException("Unexpected response from the game server");
        }

        cachedStatus = response;
        cachedStatusAt = now;
        return response;
    }

    /** True when the game server's HTTP API answers. Used instead of a process check when it runs elsewhere. */
    public boolean isReachable() {
        try {
            getSessionStatus();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Tests share the static cache
    static void clearStatusCache() {
        cachedStatus = null;
    }

    /** Removes a player; with banSeconds also bans them from rejoining for that long (the game server's own temporary ban). */
    public void kick(int refid, Integer banSeconds) {
        REST_CLIENT.get().uri(LiveSession.kickUri(gameServerBaseUrl, refid, banSeconds)).retrieve().toBodilessEntity();
        cachedStatus = null;
    }

    public void sendChat(String message) {
        REST_CLIENT.get().uri(LiveSession.chatUri(gameServerBaseUrl, message)).retrieve().toBodilessEntity();
    }

    // @TODO: Support game server basic auth
    // @TODO: Add error handling
    private List getData(String url) {
        String response = REST_CLIENT.get()
                .uri(url)
                .retrieve()
                .body(String.class);

        return this.serverResponseToJson(String.valueOf(response));
    }

    private List serverResponseToJson(String jsonString) {
        try {
            return parseListResponse(jsonString);
        } catch (JacksonException e) {
            throw new IllegalStateException("Unreadable response from the game server", e);
        }
    }

    // The game server wraps lists as {"response": {"list": [...]}}
    static List<Object> parseListResponse(String jsonString) {
        JsonNode list = RacemgrUtils.JSON.readTree(jsonString).path("response").path("list");
        if (!list.isArray()) {
            throw new IllegalStateException("Unexpected response from the game server");
        }
        return RacemgrUtils.JSON.convertValue(list, new TypeReference<ArrayList<Object>>() {});
    }

    public String getDataApiUrl(String endpoint) {
        return this.gameServerListBaseUrl + "/" + endpoint;
    }

    public List getTracks() {
        String endpoint = GameServerEndpoints.TRACKS.getEndpoint();
        String apiUrl = this.getDataApiUrl(endpoint);
        List apiData = this.getData(apiUrl);
        return apiData;
    }

    public List getTracksFiltered(int count, boolean random, boolean allowKarts) {
        List apiData = this.getTracks();
        if (allowKarts == false) {
            apiData = withoutKartTracks(apiData);
        }
        return RacemgrUtils.filterList(apiData, count, random);
    }

    private List withoutKartTracks(List tracks) {
        Predicate byTrack = trackElement -> {
            String track = RacemgrUtils.getValueFromObj("name", trackElement);
            return !track.toLowerCase().contains("kart") && !track.toLowerCase().contains("speedland");
        };
        var filteredTracks = tracks.stream().filter(byTrack)
                .collect(Collectors.toList());
        return (List) filteredTracks;
    }

    public List getVehicles() {
        String endpoint = GameServerEndpoints.VEHICLES.getEndpoint();
        String apiUrl = this.getDataApiUrl(endpoint);
        List apiData = this.getData(apiUrl);
        return apiData;
    }

    public List getVehiclesFiltered(int count, boolean random) {
        List apiData = this.getVehicles();
        return RacemgrUtils.filterList(apiData, count, random);
    }

    public List getLiveries() {
        String endpoint = GameServerEndpoints.LIVERIES.getEndpoint();
        String apiUrl = this.getDataApiUrl(endpoint);
        List apiData = this.getData(apiUrl);
        return apiData;
    }

    public List getVehicleClasses() {
        String endpoint = GameServerEndpoints.VEHICLE_CLASSES.getEndpoint();
        String apiUrl = this.getDataApiUrl(endpoint);
        List apiData = this.getData(apiUrl);
        return apiData;
    }

    public List getVehicleClassesFiltered(int count, boolean random, boolean allowKarts) {
        List apiData = this.getVehicleClasses();
        if (allowKarts == false) {
            apiData = vehicleClassesWithoutKarts(apiData);
        }
        return RacemgrUtils.filterList(apiData, count, random);
    }

    private List vehicleClassesWithoutKarts(List vehicleClasses) {
        Predicate byVehicleClass = vehicleClassElement -> {
            String vehicleClass = RacemgrUtils.getValueFromObj("name", vehicleClassElement);
            return !vehicleClass.toLowerCase().contains("kart");
        };
        var filteredVehicleClasses = vehicleClasses.stream().filter(byVehicleClass)
                .collect(Collectors.toList());
        return (List) filteredVehicleClasses;
    }

    public Object getRotation() throws IOException {
        File file = GameServerPaths.defaultRotationFile().toFile();
        String jsonString = FileUtils.readFileToString(file, "UTF-8");
        Object obj = RacemgrUtils.jsonStrToObj(jsonString);
        return obj;
    }

    public JsonNode getStats() throws IOException {
        File file = GameServerPaths.statsFile().toFile();
        String dataString = FileUtils.readFileToString(file, "UTF-8");
        return parseStats(dataString);
    }

    // The file is JSON-ish, but has comments around the JSON that need removed before parsing
    static JsonNode parseStats(String dataString) throws IOException {
        var jsonStart = dataString.indexOf("{");
        var jsonEnd = dataString.lastIndexOf("}");
        if (jsonStart < 0 || jsonEnd < jsonStart) {
            throw new IOException("No JSON found in the game server stats file");
        }
        JsonNode stats;
        try {
            stats = RacemgrUtils.JSON.readTree(dataString.substring(jsonStart, jsonEnd + 1)).path("stats");
        } catch (JacksonException e) {
            throw new IOException("Unreadable game server stats file", e);
        }
        if (stats.isMissingNode()) {
            throw new IOException("No stats found in the game server stats file");
        }
        return stats;
    }

    public List getWeather() {
        String endpoint = GameServerEndpoints.WEATHER.getEndpoint();
        String apiUrl = this.getDataApiUrl(endpoint);
        List apiData = this.getData(apiUrl);
        return apiData;
    }

    public List getWeatherFiltered(int count, boolean random, boolean withoutRain) {
        List filteredApiData = this.getWeather();
        if (withoutRain) {
            filteredApiData = this.weatherWithoutRain(filteredApiData);
        }

        return RacemgrUtils.filterList(filteredApiData, count, random);
    }

    private List weatherWithoutRain(List weatherData) {
        List<String> rainyWeatherNames = Arrays.asList(
                "LightRain",
                "FogWithRain",
                "HeavyFogWithRain",
                "HeavyFog",
                "ThunderStorm",
                "Rain",
                "Storm",
                "Random"
        );

        Predicate byWeather = weatherElement -> {
            String weather = RacemgrUtils.getValueFromObj("name", weatherElement);
            return !rainyWeatherNames.contains(weather);
        };
        var filteredWeather = weatherData.stream().filter(byWeather)
                .collect(Collectors.toList());
        return (List) filteredWeather;
    }
}