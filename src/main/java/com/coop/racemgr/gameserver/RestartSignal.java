package com.coop.racemgr.gameserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Restarting a game server that runs in its own container (RM_GAMESERVER_CONTROL=external): Race Manager writes a
 * small file into the game server's folder, which both containers share, and the game server's entrypoint
 * (docker/game-server/entrypoint.sh) stops and starts the server when it sees it. No network port is involved.
 * Enabled with RM_GAMESERVER_RESTART=signal, once that entrypoint is in place.
 */
public final class RestartSignal {
    public static final String FILE_NAME = ".racemanager-restart";

    private RestartSignal() {}

    public static boolean isEnabled() {
        return GameServerMgr.isExternal() && "signal".equalsIgnoreCase(GameServerSettings.get("RM_GAMESERVER_RESTART"));
    }

    static Path file() {
        return GameServerPaths.root().resolve(FILE_NAME);
    }

    /** A restart was asked for and the game server hasn't picked it up yet. */
    public static boolean isPending() {
        return Files.exists(file());
    }

    /** @param resetRotation also start the rotation again from its first round */
    public static void request(boolean resetRotation) throws IOException {
        Files.writeString(file(), resetRotation ? "reset-rotation\n" : "restart\n", StandardCharsets.UTF_8);
    }
}
