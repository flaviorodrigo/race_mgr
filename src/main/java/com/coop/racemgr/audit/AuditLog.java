package com.coop.racemgr.audit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class AuditLog {
    private static final Logger logger = LogManager.getLogger(AuditLog.class);
    private static final int MAX_DETAIL = 300;

    @Autowired
    private AuditEventRepository repository;

    /** Never throws: a problem writing the log must not stop the action being logged. */
    public void record(String actor, String action, String detail) {
        String cleaned = detail == null ? "" : detail.replaceAll("\\p{Cntrl}", " ");
        if (cleaned.length() > MAX_DETAIL) {
            cleaned = cleaned.substring(0, MAX_DETAIL) + "...";
        }
        logger.info("AUDIT " + actor + " " + action + (cleaned.isEmpty() ? "" : " " + cleaned));
        try {
            repository.save(new AuditEvent(Instant.now(), actor, action, cleaned));
        } catch (Exception e) {
            logger.warn("Could not save audit event: " + e);
        }
    }

    public List<AuditEvent> recent(int limit) {
        return repository.findAllByOrderByTimeDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 500))));
    }
}
