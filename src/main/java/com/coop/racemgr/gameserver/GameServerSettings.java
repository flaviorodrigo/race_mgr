package com.coop.racemgr.gameserver;

/**
 * Game server connection settings. Environment variables are the real configuration; a Java system
 * property with the same name wins, which is what the tests use.
 */
final class GameServerSettings {
    private GameServerSettings() {}

    static String get(String name) {
        String value = System.getProperty(name);
        return value != null ? value : System.getenv(name);
    }

    /** True when something other than Race Manager starts, stops and restarts the game server. */
    static boolean isExternalControl() {
        return "external".equalsIgnoreCase(get("RM_GAMESERVER_CONTROL"));
    }
}
