package com.coop.racemgr.stats;

import com.coop.racemgr.gameserver.GameServerPaths;
import com.coop.racemgr.model.Race;
import com.coop.racemgr.utils.RacemgrUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;

/**
 * Where the ams2_stats addon's file is and how it is read. Chosen with RM_STATS_SOURCE=ams2_stats (the default is
 * Reiza's sms_stats). RM_STATS_TIMEZONE names the time zone the server's clock is in (the addon writes local times).
 */
public final class Ams2StatsSource {
    public static final String FILE_NAME = "ams2_stats_data.json";

    private static volatile long lastModified = -1;
    private static volatile List<Race> lastRaces = List.of();

    private Ams2StatsSource() {}

    public static boolean isSelected() {
        String source = System.getProperty("RM_STATS_SOURCE", System.getenv("RM_STATS_SOURCE"));
        return "ams2_stats".equalsIgnoreCase(source);
    }

    static ZoneId zone() {
        String name = System.getProperty("RM_STATS_TIMEZONE", System.getenv("RM_STATS_TIMEZONE"));
        try {
            return name == null || name.isBlank() ? ZoneId.systemDefault() : ZoneId.of(name.trim());
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    /** The races in the addon's file. The file is only parsed again when it has changed (this runs every second). */
    public static List<Race> readRaces() throws IOException {
        Path file = GameServerPaths.root().resolve("lua_config").resolve(FILE_NAME);
        long modified = Files.getLastModifiedTime(file).toMillis();
        if (modified == lastModified) {
            return lastRaces;
        }

        // The file starts and ends with // comment lines; the lenient mapper reads past them
        var races = Ams2StatsImporter.toRaces(RacemgrUtils.JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)), zone());
        lastRaces = races;
        lastModified = modified;
        return races;
    }
}
