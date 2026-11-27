package com.coop.racemgr.notify;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SteamServerCheckTest {
    private HttpServer server;
    private String steamResponse = "{\"response\":{\"success\":true,\"servers\":[]}}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ip", ex -> {
            byte[] b = "71.178.26.6\n".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.createContext("/ISteamApps/GetServersAtAddress/v0001/", ex -> {
            byte[] b = steamResponse.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void detectsAms2ServerOnSteamMasterList() {
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        var check = new SteamServerCheck("", base + "/ip", base);

        assertThat(check.checkReachable()).isEmpty();

        steamResponse = """
                {"response":{"success":true,"servers":[
                  {"addr":"71.178.26.6:27016","name":"McRod AMS2 Server","appid":1066890,"players":0,"max":22,"gametype":"running,joinable","map":"Brasilia_Full"}
                ]}}
                """;
        var listing = check.checkReachable().orElseThrow();
        assertThat(listing.name()).isEqualTo("McRod AMS2 Server");
        assertThat(listing.addr()).isEqualTo("71.178.26.6:27016");
        assertThat(listing.map()).isEqualTo("Brasilia_Full");
        assertThat(listing.maxPlayers()).isEqualTo(22);
    }
}
