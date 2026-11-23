package com.coop.racemgr;

import com.coop.racemgr.gameserver.GameServerMgr;
import com.coop.racemgr.jobs.RaceEventProcessor;
import com.coop.racemgr.notify.DiscordNotifier;
import com.coop.racemgr.repositories.RaceMgrConfigRepository;
import com.coop.racemgr.repositories.RaceRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ComponentScan(basePackages = {"com.coop.racemgr"})
public class SpringConfiguration {
    private static final Logger logger = LogManager.getLogger(RacemgrApplication.class);

    @Autowired
    private RaceRepository raceRepository;

    @Autowired
    private RaceMgrConfigRepository raceMgrConfigRepository;

    @Autowired
    private DiscordNotifier discordNotifier;

    private Boolean lastOnline;
    private int disagreements;

    // Processes game server race events. Runs 1s after the previous run completes. This calls the
    // processor directly: it used to log in to its own REST API every second (minting a JWT and
    // running several BCrypt hashes each time), which needed the admin credentials in this path.
    @Scheduled(fixedDelay = 1000)
    public void scheduleRaceEventProcessing() {
        try {
            new RaceEventProcessor(raceRepository, raceMgrConfigRepository, discordNotifier).processRaces();
        } catch (Exception e) {
            logger.warn("Race event processing failed: " + e);
        }
    }

    // Tells Discord when the game server goes down or comes back. A change has to be seen twice in a row
    // (30 seconds) so a hiccup doesn't produce a message.
    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void watchGameServer() {
        if (!discordNotifier.isEnabled()) {
            return;
        }

        try {
            boolean online = GameServerMgr.isRunning();
            if (lastOnline == null) {
                lastOnline = online;
            } else if (online == lastOnline) {
                disagreements = 0;
            } else if (++disagreements >= 2) {
                lastOnline = online;
                disagreements = 0;
                discordNotifier.serverState(online);
            }
        } catch (Exception e) {
            logger.warn("Could not check the game server: " + e);
        }
    }
}
