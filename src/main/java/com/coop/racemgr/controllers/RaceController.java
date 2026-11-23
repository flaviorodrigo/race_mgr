package com.coop.racemgr.controllers;

import com.coop.racemgr.RacemgrApplication;
import com.coop.racemgr.model.Race;
import com.coop.racemgr.repositories.RaceMgrConfigRepository;
import com.coop.racemgr.repositories.RaceRepository;
import com.coop.racemgr.utils.RacemgrUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RaceController {
    @Autowired
    private RaceRepository raceRepository;

    @Autowired
    private RaceMgrConfigRepository raceMgrConfigRepository;

    // The leaderboard UI loads every race for a session/rotation in one request
    private static final int MAX_PAGE_SIZE = 10000;

    private static final Logger logger = LogManager.getLogger(RacemgrApplication.class);

    /** PUBLIC ENDPOINTS **/

    @GetMapping("/api/v1/race/events")
    public ResponseEntity<Object> raceEventList(
            @RequestParam(required = false, defaultValue = "") String raceSessionId,
            @RequestParam(required = false, defaultValue = "") String raceRotationId,
            @RequestParam(defaultValue = "0") Integer page,
            @RequestParam(defaultValue = "10") Integer limit
    ) {
        Pageable paging = RacemgrUtils.boundedPageRequest(page, limit, MAX_PAGE_SIZE);
        Page<Race> raceSessions;

        try {
            if (!raceSessionId.equals("") && !raceRotationId.equals("")) {
                // Filter by session and rotation
                logger.info("Filter by session and rotation");
                raceSessions = raceRepository.findAllBySessionIdAndRotationId(raceSessionId, raceRotationId, paging);
            } else if (!raceSessionId.equals("") && raceRotationId.equals("")) {
                logger.info("Filter by session");
                // Filter by session
                raceSessions = raceRepository.findAllBySessionId(raceSessionId, paging);
                // Filter by rotation
            } else if (raceSessionId.equals("") && !raceRotationId.equals("")) {
                logger.info("Filter by rotation");
                raceSessions = raceRepository.findAllByRotationId(raceRotationId, paging);
            } else {
                // No filter
                logger.info("Filter none");
                raceSessions = raceRepository.findAll(paging);
            }

            return ResponseHandler.generateResponse("Race event list", HttpStatus.OK, raceSessions);
        } catch (Exception e) {
            logger.error(e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }
}
