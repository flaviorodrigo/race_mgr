package com.coop.racemgr.notify;

import com.coop.racemgr.model.Race;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordNotifierTest {
    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/webhooks/1/token", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private DiscordNotifier notifier() {
        return new DiscordNotifier("http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/1/token", false);
    }

    @Test
    void isOffWithoutAWebhookOrWithAnAddressThatIsNotDiscords() {
        assertThat(new DiscordNotifier("", true).isEnabled()).isFalse();
        assertThat(new DiscordNotifier("https://evil.example/api/webhooks/1/x", true).isEnabled()).isFalse();
        assertThat(new DiscordNotifier("http://discord.com/api/webhooks/1/x", true).isEnabled()).isFalse();
        assertThat(new DiscordNotifier("https://discord.com/other/path", true).isEnabled()).isFalse();
        assertThat(new DiscordNotifier("https://discord.com.evil.example/api/webhooks/1/x", true).isEnabled()).isFalse();
        assertThat(new DiscordNotifier("https://discord.com/api/webhooks/123/abc", true).isEnabled()).isTrue();
        assertThat(new DiscordNotifier("https://discordapp.com/api/webhooks/123/abc", true).isEnabled()).isTrue();
    }

    @Test
    void postsARaceResultThatCannotPingAnyone() throws Exception {
        var notifier = notifier();
        notifier.raceFinished("Spa-Francorchamps", 0, List.of(
                new DiscordNotifier.Result("@everyone **Ayrton**", 1, 117285L),
                new DiscordNotifier.Result("Alain", 2, 117000L),
                new DiscordNotifier.Result("Nigel", 3, null),
                new DiscordNotifier.Result("Mika", 4, null)));
        assertThat(notifier.drain(5)).isTrue();

        assertThat(bodies).hasSize(1);
        JsonNode body = JsonMapper.shared().readTree(bodies.get(0));
        assertThat(body.path("allowed_mentions").path("parse").isArray()).isTrue();
        assertThat(body.path("allowed_mentions").path("parse").size()).isZero();
        String text = body.path("embeds").get(0).path("description").asString();
        assertThat(text).contains("🥇").contains("🥈").contains("🥉").doesNotContain("Mika");
        // Markdown and mentions in a driver name are neutralised
        assertThat(text).contains("\\@everyone \\*\\*Ayrton\\*\\*");
        assertThat(text).contains("Fastest lap: Alain 1:57.000");
        assertThat(body.path("embeds").get(0).path("title").asString()).contains("Spa-Francorchamps");
    }

    @Test
    void postsServerStateChanges() throws Exception {
        var notifier = notifier();
        notifier.serverState(false);
        notifier.serverState(true);
        assertThat(notifier.drain(5)).isTrue();

        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(0)).contains("offline");
        assertThat(bodies.get(1)).contains("online");
    }

    @Test
    void anUnreachableWebhookNeverThrows() throws Exception {
        server.stop(0);
        var notifier = notifier();
        notifier.serverState(true);
        assertThat(notifier.drain(10)).isTrue();
    }

    @Test
    void formatsLapTimesAndNames() {
        assertThat(DiscordNotifier.lap(117285)).isEqualTo("1:57.285");
        assertThat(DiscordNotifier.lap(59001)).isEqualTo("0:59.001");
        assertThat(DiscordNotifier.clean("x".repeat(50))).hasSize(33);
        assertThat(DiscordNotifier.clean("a\nb")).isEqualTo("a b");
    }

    @Test
    void onlyAnnouncesRecentFinishedRaces() {
        long now = 1_800_000_000L;
        Map<String, Object> race1 = Map.of("results", List.of(
                Map.of("name", "A", "attributes", Map.of("RacePosition", 1, "FastestLapTime", 90000)),
                Map.of("name", "B", "attributes", Map.of("RacePosition", 2, "FastestLapTime", 91000))));

        var race = new Race();
        race.setFinished(true);
        race.setEnd_time(String.valueOf(now - 60));
        race.setStages(Map.of("race1", race1));
        race.setSetup(Map.of("TrackId", 42));

        var prepared = RaceAnnouncement.prepare(race, now).orElseThrow();
        assertThat(prepared.trackId()).isEqualTo("42");
        assertThat(prepared.results()).extracting(DiscordNotifier.Result::name).containsExactly("A", "B");

        // Old (history imported after a restart), unfinished, or without a race stage: stay quiet
        race.setEnd_time(String.valueOf(now - 3 * 3600));
        assertThat(RaceAnnouncement.prepare(race, now)).isEmpty();
        race.setEnd_time(String.valueOf(now - 60));
        race.setFinished(false);
        assertThat(RaceAnnouncement.prepare(race, now)).isEmpty();
        race.setFinished(true);
        race.setStages(Map.of("qualifying1", race1));
        assertThat(RaceAnnouncement.prepare(race, now)).isEmpty();
    }
}
