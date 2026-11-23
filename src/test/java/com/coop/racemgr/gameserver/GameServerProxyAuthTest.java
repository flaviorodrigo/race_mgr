package com.coop.racemgr.gameserver;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GameServerProxyAuthTest {
    private HttpServer server;
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastPath = new AtomicReference<>();

    @BeforeEach
    void startFakeGameServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api", exchange -> {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastPath.set(exchange.getRequestURI().toString());
            byte[] body = "{\"result\":\"ok\",\"response\":{\"attributes\":{\"SessionStage\":\"Lobby\"},\"members\":[]}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        System.setProperty("RM_GAMESERVER_HOST", "127.0.0.1");
        System.setProperty("RM_GAMESERVER_PORT", String.valueOf(server.getAddress().getPort()));
        GameServerProxy.clearStatusCache();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        for (String key : new String[]{"RM_GAMESERVER_HOST", "RM_GAMESERVER_PORT", "RM_GAMESERVER_USER", "RM_GAMESERVER_PASSWORD"}) {
            System.clearProperty(key);
        }
        GameServerProxy.clearStatusCache();
    }

    @Test
    void sendsBasicAuthWhenCredentialsAreConfigured() {
        System.setProperty("RM_GAMESERVER_USER", "admin");
        System.setProperty("RM_GAMESERVER_PASSWORD", "p@ss:word!");

        new GameServerProxy().getSessionStatus();

        String expected = "Basic " + Base64.getEncoder().encodeToString("admin:p@ss:word!".getBytes(StandardCharsets.UTF_8));
        assertThat(lastAuthorization.get()).isEqualTo(expected);
        assertThat(lastPath.get()).startsWith("/api/session/status");
    }

    @Test
    void sendsNoAuthorizationHeaderByDefault() {
        new GameServerProxy().getSessionStatus();

        assertThat(lastAuthorization.get()).isNull();
    }

    @Test
    void isReachableReflectsWhetherTheApiAnswers() {
        assertThat(new GameServerProxy().isReachable()).isTrue();

        server.stop(0);
        GameServerProxy.clearStatusCache();
        assertThat(new GameServerProxy().isReachable()).isFalse();
    }

    @Test
    void externalControlModeReadsTheSetting() {
        assertThat(GameServerMgr.isExternal()).isFalse();
        System.setProperty("RM_GAMESERVER_CONTROL", "external");
        try {
            assertThat(GameServerMgr.isExternal()).isTrue();
            // Nothing is started or stopped, and "running" follows the API
            assertThat(GameServerMgr.isRunning()).isTrue();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        } finally {
            System.clearProperty("RM_GAMESERVER_CONTROL");
        }
    }
}
