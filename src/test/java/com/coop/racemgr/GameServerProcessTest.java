package com.coop.racemgr;

import com.coop.racemgr.gameserver.GameServerProcess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.File;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisabledOnOs(OS.WINDOWS)
class GameServerProcessTest {
    // `sleep 300 <marker>` stands in for `wine DedicatedServerCmd.exe`
    private GameServerProcess fakeServer() {
        return new GameServerProcess(List.of("sleep", "300"), new File(System.getProperty("java.io.tmpdir")), null);
    }

    @Test
    void startStopAndRestart() throws Exception {
        GameServerProcess server = fakeServer();
        assertThat(server.isRunning()).isFalse();

        server.start();
        assertThat(server.isRunning()).isTrue();

        server.stop();
        assertThat(server.isRunning()).isFalse();

        server.start();
        assertThat(server.isRunning()).isTrue();
        server.stop();
    }

    @Test
    void startingTwiceReplacesTheRunningProcess() throws Exception {
        GameServerProcess server = fakeServer();
        server.start();
        server.start();
        assertThat(server.isRunning()).isTrue();
        server.stop();
        assertThat(server.isRunning()).isFalse();
    }

    @Test
    void finds_and_stops_a_server_it_did_not_start() throws Exception {
        // A process started elsewhere whose last argument ends with the server exe name. (`sh -c <script> <name>`
        // gives the shell that name as its last argument; GNU `sleep` would refuse an extra argument, BSD's ignores it.
        // The `; true` keeps the shell around instead of letting it exec the sleep.)
        Process external = new ProcessBuilder("sh", "-c", "sleep 300; true", "Fake.Server.exe").start();
        try {
            GameServerProcess server = new GameServerProcess(List.of("sleep", "300"),
                    new File(System.getProperty("java.io.tmpdir")), "Fake.Server.exe");
            assertThat(server.isRunning()).isTrue();
            server.stop();
            assertThat(external.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {
            external.descendants().forEach(ProcessHandle::destroyForcibly);
            external.destroyForcibly();
        }
    }
}
