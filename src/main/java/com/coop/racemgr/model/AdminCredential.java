package com.coop.racemgr.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** The admin password once it has been changed in the UI. Overrides RM_ADMIN_PASSWORD. Only a BCrypt hash is stored. */
@Data
@Document("admin_credentials")
public class AdminCredential {
    public static final String ID = "1";

    @Id
    private String id = ID;
    private String passwordHash;
    // Also the "credential version": tokens issued before a change stop working
    private long updated;
}
