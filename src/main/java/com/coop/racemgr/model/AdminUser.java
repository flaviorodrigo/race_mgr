package com.coop.racemgr.model;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** An extra admin added by the owner. Only a BCrypt hash of the password is stored. */
@Data
@Document("admin_users")
public class AdminUser {
    public static final int MAX_USERS = 20;

    @Id
    private String id;
    // Stored in lower case
    @Indexed(unique = true)
    private String username;
    private String passwordHash;
    // ADMIN or STEWARD
    private String role;
    // Changes whenever the password does; sessions issued before are signed out
    private long credentialVersion;
    private Instant created;
}
