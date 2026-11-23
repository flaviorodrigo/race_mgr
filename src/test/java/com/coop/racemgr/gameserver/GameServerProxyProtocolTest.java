package com.coop.racemgr.gameserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real AMS2 dedicated server (libwebsockets) hangs up on requests that ask to upgrade to HTTP/2 (which the JDK
 * HTTP client does by default over plain HTTP). This fake behaves the same way.
 */
class GameServerProxyProtocolTest {
    private ServerSocket server;
    private Thread acceptor;
    private volatile int upgradeAttempts;

    @BeforeEach
    void start() throws Exception {
        server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        acceptor = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
                    boolean upgrade = false;
                    for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                        if (line.toLowerCase(Locale.ROOT).startsWith("upgrade:")) {
                            upgrade = true;
                        }
                    }
                    if (upgrade) {
                        upgradeAttempts++;
                        continue; // close without answering
                    }
                    byte[] body = "{\"result\":\"ok\",\"response\":{\"state\":\"Idle\",\"attributes\":{},\"members\":[]}}".getBytes(StandardCharsets.UTF_8);
                    String head = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
                    socket.getOutputStream().write(head.getBytes(StandardCharsets.ISO_8859_1));
                    socket.getOutputStream().write(body);
                } catch (Exception ignored) {
                    // closed while stopping
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();

        System.setProperty("RM_GAMESERVER_HOST", "127.0.0.1");
        System.setProperty("RM_GAMESERVER_PORT", String.valueOf(server.getLocalPort()));
        GameServerProxy.clearStatusCache();
    }

    @AfterEach
    void stop() throws Exception {
        server.close();
        System.clearProperty("RM_GAMESERVER_HOST");
        System.clearProperty("RM_GAMESERVER_PORT");
        GameServerProxy.clearStatusCache();
    }

    @Test
    void talksPlainHttp11SoTheGameServerDoesNotHangUp() {
        assertThat(new GameServerProxy().isReachable()).isTrue();
        assertThat(upgradeAttempts).as("requests that asked for an HTTP/2 upgrade").isZero();
    }
}
