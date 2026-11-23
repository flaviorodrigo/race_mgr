package com.coop.racemgr.jobs;

import com.coop.racemgr.RacemgrApplication;
import com.coop.racemgr.gameserver.GameServerProxy;
import com.coop.racemgr.model.Race;
import com.coop.racemgr.repositories.RaceMgrConfigRepository;
import com.coop.racemgr.repositories.RaceRepository;
import com.coop.racemgr.notify.DiscordNotifier;
import com.coop.racemgr.notify.RaceAnnouncement;
import com.coop.racemgr.utils.RacemgrUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import tools.jackson.databind.JsonNode;

import java.io.IOException;

public class RaceEventProcessor {
    private RaceRepository raceRepository;
    private RaceMgrConfigRepository raceMgrConfigRepository;
    private GameServerProxy gameServerProxy;
    private DiscordNotifier notifier;
    private static volatile boolean warnedNotConfigured = false;

    private static final Logger logger = LogManager.getLogger(RacemgrApplication.class);

    // @FIXME: Figure out how to autowire these
    public RaceEventProcessor(RaceRepository raceRepository, RaceMgrConfigRepository raceMgrConfigRepository) {
        this(raceRepository, raceMgrConfigRepository, null);
    }

    public RaceEventProcessor(RaceRepository raceRepository, RaceMgrConfigRepository raceMgrConfigRepository, DiscordNotifier notifier) {
        this.notifier = notifier;
        this.gameServerProxy = new GameServerProxy();
        this.raceRepository = raceRepository;
        this.raceMgrConfigRepository = raceMgrConfigRepository;
    }

    private void announce(Race race) {
        if (notifier == null || !notifier.isEnabled()) {
            return;
        }

        try {
            RaceAnnouncement.prepare(race, System.currentTimeMillis() / 1000).ifPresent(p -> {
                String track = null;
                try {
                    track = trackName(p.trackId());
                } catch (Exception e) {
                    // The announcement is still useful without the track name
                }
                notifier.raceFinished(track, 0, p.results());
            });
        } catch (Exception e) {
            logger.warn("Could not announce a race: " + e);
        }
    }

    private String trackName(String trackId) {
        if (trackId == null) {
            return null;
        }
        for (Object track : gameServerProxy.getTracks()) {
            if (track instanceof java.util.Map<?, ?> map && trackId.equals(String.valueOf(map.get("id")))) {
                return String.valueOf(map.get("name")).replace('_', ' ');
            }
        }
        return null;
    }

    // (String == "" compared object identity, so an empty or missing id was never detected)
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // NOTE: The game server decides when to prune event history. To avoid losing data
    // between crashes and restarts we reprocess/check all events per cycle. Only unseen
    // events are saved. For performance, it is good to set the game server
    public void processRaces() throws IOException {
        var currentRmc = raceMgrConfigRepository.findItemById("1");
        if (currentRmc == null || isBlank(currentRmc.getActiveRaceSessionId()) || isBlank(currentRmc.getActiveRaceRotationId())) {
            // Runs every second, so only say it once until a session/rotation gets configured
            if (!warnedNotConfigured) {
                logger.warn("Event processing skipped! No activeRaceSessionId & activeRaceRotationId set. Use the Race Manager admin UI to create/update these before racing.");
                warnedNotConfigured = true;
            }
        }  else {
            warnedNotConfigured = false;
            JsonNode stats = this.gameServerProxy.getStats();

            // As long as we have history, process it
            try {
                JsonNode raceHistory = stats.path("history");
                if (!raceHistory.isArray()) {
                    throw new IllegalStateException("No race history present");
                }

                for (JsonNode raceData : raceHistory) {
                    Race race = RacemgrUtils.JSON.treeToValue(raceData, Race.class);
                    race.setRaceId();
                    race.setRaceSessionId(currentRmc.getActiveRaceSessionId());
                    race.setRaceRotationId(currentRmc.getActiveRaceRotationId());

                    var r = raceRepository.findItemByRaceId(race.getRace_id());
                    if (r == null) {
                        raceRepository.save(race);
                        announce(race);
                    }
                }

                logger.debug("Race event processing cycle complete: " + RacemgrUtils.getCurrentDateTime());
            } catch (Exception e) {
                // Not a real error, there is no history (race data) present on in the dedicated server
                logger.debug("Race event processing, nothing to process yet: " + RacemgrUtils.getCurrentDateTime());
            }
        }
    }
}
