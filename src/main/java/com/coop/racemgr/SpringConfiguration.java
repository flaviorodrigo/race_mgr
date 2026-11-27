package com.coop.racemgr;

import com.coop.racemgr.gameserver.GameServerMgr;
import com.coop.racemgr.jobs.RaceEventProcessor;
import com.coop.racemgr.notify.DiscordNotifier;
import com.coop.racemgr.notify.SteamServerCheck;
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

    @Autowired
    private SteamServerCheck steamServerCheck;

    private Boolean lastOnline;
    private int disagreements;
    private boolean pendingSteamCheck;
    private boolean notifyDiscordOnSteam;
    private int steamChecks;
    private int steamRefreshTicks;

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

    // Tells Discord when the game server goes down or comes back and keeps Steam Master Server
    // reachability updated for both Discord and the UI header. A backend state change has to be seen
    // twice in a row (30 seconds) so a hiccup doesn't produce a false alert.
    @Scheduled(fixedDelay = 15000, initialDelay = 15000)
    public void watchGameServer() {
        try {
            boolean online = GameServerMgr.isRunning();
            if (lastOnline == null) {
                lastOnline = online;
                if (online) {
                    steamServerCheck.markChecking();
                    pendingSteamCheck = true;
                    notifyDiscordOnSteam = false;
                    steamChecks = 0;
                } else {
                    steamServerCheck.markOffline();
                }
            } else if (online == lastOnline) {
                disagreements = 0;
            } else if (++disagreements >= 2) {
                lastOnline = online;
                disagreements = 0;
                if (online) {
                    if (discordNotifier.isEnabled()) {
                        discordNotifier.serverBackendOnline();
                    }
                    steamServerCheck.markChecking();
                    pendingSteamCheck = true;
                    notifyDiscordOnSteam = true;
                    steamChecks = 0;
                    steamRefreshTicks = 0;
                } else {
                    pendingSteamCheck = false;
                    notifyDiscordOnSteam = false;
                    steamChecks = 0;
                    steamRefreshTicks = 0;
                    steamServerCheck.markOffline();
                    if (discordNotifier.isEnabled()) {
                        discordNotifier.serverState(false);
                    }
                }
            }

            if (Boolean.TRUE.equals(lastOnline)) {
                if (pendingSteamCheck) {
                    var listing = steamServerCheck.checkReachable();
                    if (listing.isPresent()) {
                        pendingSteamCheck = false;
                        steamRefreshTicks = 0;
                        if (notifyDiscordOnSteam && discordNotifier.isEnabled()) {
                            discordNotifier.serverSteamReachable(listing.get());
                        }
                        notifyDiscordOnSteam = false;
                    } else if (++steamChecks >= 20) {
                        pendingSteamCheck = false;
                        steamRefreshTicks = 0;
                        steamServerCheck.markUnlisted();
                        if (notifyDiscordOnSteam && discordNotifier.isEnabled()) {
                            discordNotifier.serverSteamTimeout();
                        }
                        notifyDiscordOnSteam = false;
                    }
                } else if (++steamRefreshTicks >= 4) {
                    steamRefreshTicks = 0;
                    steamServerCheck.checkReachable();
                }
            }
        } catch (Exception e) {
            logger.warn("Could not check the game server: " + e);
        }
    }
}
