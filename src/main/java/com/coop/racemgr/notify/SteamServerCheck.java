package com.coop.racemgr.notify;

import com.coop.racemgr.utils.RacemgrUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Optional;

/**
 * Checks Valve's Steam Master Server ({@code ISteamApps/GetServersAtAddress}) to confirm when the AMS2 dedicated
 * server (appid 1066890) is publicly listed and joinable from the in-game multiplayer browser.
 */
@Component
public class SteamServerCheck {
    static final int AMS2_APP_ID = 1066890;
    private static final long IP_CACHE_MS = 10 * 60 * 1000L;

    public enum SteamStatus {
        UNKNOWN,
        CHECKING,
        REACHABLE,
        UNLISTED
    }

    public record Listing(String name, String addr, String map, int players, int maxPlayers, String gameType) {}

    private final RestClient client;
    private final String ipLookupUrl;
    private final String steamApiBase;
    private final String configuredPublicIp;
    private volatile String cachedIp;
    private volatile long cachedIpAt;
    private volatile SteamStatus status = SteamStatus.UNKNOWN;
    private volatile Listing lastListing;
    private volatile Long lastCheckedAt;

    @Autowired
    public SteamServerCheck(@Value("${RM_PUBLIC_IP:}") String configuredPublicIp) {
        this(configuredPublicIp, "https://api.ipify.org", "https://api.steampowered.com");
    }

    SteamServerCheck(String configuredPublicIp, String ipLookupUrl, String steamApiBase) {
        this.configuredPublicIp = configuredPublicIp == null ? "" : configuredPublicIp.trim();
        this.ipLookupUrl = ipLookupUrl;
        this.steamApiBase = steamApiBase;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    public SteamStatus getStatus() {
        return status;
    }

    public Listing getLastListing() {
        return lastListing;
    }

    public Long getLastCheckedAt() {
        return lastCheckedAt;
    }

    public void markChecking() {
        status = SteamStatus.CHECKING;
        lastListing = null;
    }

    public void markUnlisted() {
        status = SteamStatus.UNLISTED;
        lastListing = null;
    }

    public void markOffline() {
        status = SteamStatus.UNKNOWN;
        lastListing = null;
        lastCheckedAt = null;
    }

    public Optional<Listing> checkReachable() {
        lastCheckedAt = System.currentTimeMillis();
        try {
            String ip = resolvePublicIp();
            if (ip == null || ip.isBlank()) {
                if (status != SteamStatus.CHECKING) {
                    status = SteamStatus.UNLISTED;
                    lastListing = null;
                }
                return Optional.empty();
            }
            String body = client.get()
                    .uri(steamApiBase + "/ISteamApps/GetServersAtAddress/v0001/?addr=" + ip)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                if (status != SteamStatus.CHECKING) {
                    status = SteamStatus.UNLISTED;
                    lastListing = null;
                }
                return Optional.empty();
            }
            JsonNode servers = RacemgrUtils.JSON.readTree(body).path("response").path("servers");
            if (!servers.isArray()) {
                if (status != SteamStatus.CHECKING) {
                    status = SteamStatus.UNLISTED;
                    lastListing = null;
                }
                return Optional.empty();
            }
            for (JsonNode s : servers) {
                if (s.path("appid").asInt(-1) == AMS2_APP_ID) {
                    Listing listing = new Listing(
                            s.path("name").asString("AMS2 Server"),
                            s.path("addr").asString(ip),
                            s.path("map").asString(""),
                            s.path("players").asInt(0),
                            s.path("max").asInt(0),
                            s.path("gametype").asString("")
                    );
                    status = SteamStatus.REACHABLE;
                    lastListing = listing;
                    return Optional.of(listing);
                }
            }
            if (status != SteamStatus.CHECKING) {
                status = SteamStatus.UNLISTED;
                lastListing = null;
            }
            return Optional.empty();
        } catch (Exception e) {
            if (status != SteamStatus.CHECKING) {
                status = SteamStatus.UNLISTED;
                lastListing = null;
            }
            return Optional.empty();
        }
    }

    private String resolvePublicIp() {
        if (!configuredPublicIp.isEmpty()) {
            return configuredPublicIp;
        }
        long now = System.currentTimeMillis();
        if (cachedIp != null && now - cachedIpAt < IP_CACHE_MS) {
            return cachedIp;
        }
        String ip = client.get().uri(ipLookupUrl).retrieve().body(String.class);
        if (ip != null && !ip.isBlank()) {
            cachedIp = ip.trim();
            cachedIpAt = now;
            return cachedIp;
        }
        return null;
    }
}
