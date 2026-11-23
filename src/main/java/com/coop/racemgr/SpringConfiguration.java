package com.coop.racemgr;

import com.coop.racemgr.jobs.RaceEventProcessor;
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

    // Processes game server race events. Runs 1s after the previous run completes. This calls the
    // processor directly: it used to log in to its own REST API every second (minting a JWT and
    // running several BCrypt hashes each time), which needed the admin credentials in this path.
    @Scheduled(fixedDelay = 1000)
    public void scheduleRaceEventProcessing() {
        try {
            new RaceEventProcessor(raceRepository, raceMgrConfigRepository).processRaces();
        } catch (Exception e) {
            logger.warn("Race event processing failed: " + e);
        }
    }
}
