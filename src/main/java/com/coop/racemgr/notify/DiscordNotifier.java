package com.coop.racemgr.notify;

import com.coop.racemgr.utils.RacemgrUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Posts race results and server up/down notices to a Discord channel through a webhook (RM_DISCORD_WEBHOOK_URL).
 * Off unless the variable is set. The URL is a secret, so it only comes from the environment, and only Discord's
 * own webhook addresses are accepted. Sending happens off to the side and can never hold up race processing.
 */
@Component
public class DiscordNotifier {
    private static final Logger logger = LogManager.getLogger(DiscordNotifier.class);
    private static final Set<String> HOSTS = Set.of("discord.com", "ptb.discord.com", "canary.discord.com", "discordapp.com");
    private static final int MAX_NAME = 32;
    private static final int COLOR_RED = 0xff2800;
    private static final int COLOR_GREEN = 0x2e7d32;
    private static final int COLOR_GREY = 0x757575;

    private final URI webhook;
    private final RestClient client = RestClient.builder()
            .requestFactory(factory())
            .build();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "discord-notifier");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastFailureLog;

    @Autowired
    public DiscordNotifier(@Value("${RM_DISCORD_WEBHOOK_URL:}") String url) {
        this(url, true);
    }

    // Tests point this at a local server
    DiscordNotifier(String url, boolean onlyDiscordHosts) {
        URI parsed = null;
        if (url != null && !url.isBlank()) {
            parsed = parse(url.trim(), onlyDiscordHosts);
            if (parsed == null) {
                logger.warn("RM_DISCORD_WEBHOOK_URL is not a Discord webhook address (https://discord.com/api/webhooks/...), notifications are off.");
            }
        }
        this.webhook = parsed;
    }

    static URI parse(String url, boolean onlyDiscordHosts) {
        try {
            URI uri = URI.create(url);
            if (onlyDiscordHosts) {
                boolean ok = "https".equals(uri.getScheme()) && uri.getHost() != null
                        && HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
                        && uri.getPath() != null && uri.getPath().startsWith("/api/webhooks/");
                return ok ? uri : null;
            }
            return uri;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean isEnabled() {
        return webhook != null;
    }

    public record Result(String name, int position, Long fastestLapMillis) {}

    public void raceFinished(String track, int rounds, List<Result> results) {
        if (!isEnabled() || results.isEmpty()) {
            return;
        }

        List<Result> podium = results.stream().sorted(java.util.Comparator.comparingInt(Result::position)).limit(3).toList();
        StringBuilder text = new StringBuilder();
        String[] medals = {"🥇", "🥈", "🥉"};
        for (int i = 0; i < podium.size(); i++) {
            text.append(medals[i]).append(' ').append(clean(podium.get(i).name())).append('\n');
        }
        results.stream().filter(r -> r.fastestLapMillis() != null && r.fastestLapMillis() > 0)
                .min(java.util.Comparator.comparingLong(Result::fastestLapMillis))
                .ifPresent(r -> text.append("\n⏱ Fastest lap: ").append(clean(r.name())).append(' ').append(lap(r.fastestLapMillis())));

        post(embed("Race finished" + (track == null ? "" : " · " + clean(track)), text.toString().trim(), COLOR_RED));
    }

    public void serverState(boolean online) {
        if (!isEnabled()) {
            return;
        }
        post(embed(online ? "Game server is online" : "Game server is offline", "", online ? COLOR_GREEN : COLOR_GREY));
    }

    static String lap(long millis) {
        return String.format(Locale.ROOT, "%d:%02d.%03d", millis / 60000, (millis / 1000) % 60, millis % 1000);
    }

    // Driver names are chosen by players: no mentions (handled in the payload), no markdown, bounded length
    static String clean(String value) {
        String v = value == null ? "" : value.replaceAll("\\p{Cntrl}", " ").replaceAll("([\\\\*_~`|>@#:\\[\\]()])", "\\\\$1").trim();
        return v.length() > MAX_NAME ? v.substring(0, MAX_NAME) + "…" : v;
    }

    static Map<String, Object> embed(String title, String description, int color) {
        Map<String, Object> embed = new LinkedHashMap<>();
        embed.put("title", title);
        if (!description.isEmpty()) {
            embed.put("description", description);
        }
        embed.put("color", color);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", "Race Manager");
        payload.put("embeds", new ArrayList<>(List.of(embed)));
        // Nothing in the message may ping anyone, whatever a driver is called
        payload.put("allowed_mentions", Map.of("parse", List.of()));
        return payload;
    }

    private void post(Map<String, Object> payload) {
        sender.execute(() -> {
            try {
                client.post().uri(webhook).contentType(MediaType.APPLICATION_JSON)
                        .body(RacemgrUtils.JSON.writeValueAsString(payload)).retrieve().toBodilessEntity();
            } catch (Exception e) {
                // Discord rate limits and outages happen: log now and then, never throw
                long now = System.currentTimeMillis();
                if (now - lastFailureLog > 60_000) {
                    lastFailureLog = now;
                    logger.warn("Could not post to Discord: " + e.getClass().getSimpleName());
                }
            }
        });
    }

    /** Waits for queued messages (used by tests). */
    boolean drain(long seconds) throws InterruptedException {
        var latch = new java.util.concurrent.CountDownLatch(1);
        sender.execute(latch::countDown);
        return latch.await(seconds, TimeUnit.SECONDS);
    }

    private static JdkClientHttpRequestFactory factory() {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(10));
        return factory;
    }
}
