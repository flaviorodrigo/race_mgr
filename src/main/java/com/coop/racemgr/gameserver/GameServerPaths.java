package com.coop.racemgr.gameserver;

import java.nio.file.Path;

/**
 * Locations of the files Race Manager shares with the AM2DS install, built with the Path API so
 * they work on Windows, Linux, and in a container. The install root comes from
 * RM_PATH_TO_DEDICATED_SERVER.
 */
public final class GameServerPaths {
    public static final String EXE_NAME = "DedicatedServerCmd.exe";

    private GameServerPaths() {}

    public static Path root() {
        String root = GameServerSettings.get("RM_PATH_TO_DEDICATED_SERVER");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("RM_PATH_TO_DEDICATED_SERVER is not set");
        }
        return Path.of(root);
    }

    public static Path executable() {
        return root().resolve(EXE_NAME);
    }

    public static Path defaultRotationFile() {
        return root().resolve("lua").resolve("sms_rotate").resolve("sms_rotate_default_config.json");
    }

    public static Path cachedRotationFile() {
        return root().resolve("lua_config").resolve("sms_rotate_config.json");
    }

    public static Path statsFile() {
        return root().resolve("lua_config").resolve("sms_stats_data.json");
    }
}
