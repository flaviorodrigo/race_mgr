package com.coop.racemgr.audit;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** Something an admin did that is worth looking back on (kick, setting change, password change...). */
@Data
@Document("audit_events")
public class AuditEvent {
    @Id
    private String id;
    // Old events expire on their own
    @Indexed(expireAfter = "180d")
    private Instant time;
    private String actor;
    private String action;
    private String detail;

    public AuditEvent(Instant time, String actor, String action, String detail) {
        this.time = time;
        this.actor = actor;
        this.action = action;
        this.detail = detail;
    }
}
