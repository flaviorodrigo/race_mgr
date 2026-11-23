package com.coop.racemgr.service;

import java.util.ArrayList;

import com.coop.racemgr.RacemgrApplication;
import com.coop.racemgr.model.AdminCredential;
import com.coop.racemgr.repositories.AdminCredentialRepository;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class JwtUserDetailsService implements UserDetailsService {
    private static final Logger logger = LogManager.getLogger(RacemgrApplication.class);

    private final String adminUser;
    private final boolean resetStoredPassword;

    // BCrypt hash of the admin password. Starts as the RM_ADMIN_PASSWORD hash, replaced by the stored one
    // when the password has been changed in the UI.
    private volatile String hashedAdminPassword;
    // Changes whenever the password does; tokens carry it so a password change signs every session out
    private volatile long credentialVersion = 0;

    @Autowired
    private AdminCredentialRepository adminCredentialRepository;

    public JwtUserDetailsService(@Value("${RM_ADMIN_USER:}") String adminUser,
                                 @Value("${RM_ADMIN_PASSWORD:}") String adminPassword,
                                 @Value("${RM_ADMIN_PASSWORD_RESET:false}") boolean resetStoredPassword) {
        this.adminUser = adminUser;
        this.resetStoredPassword = resetStoredPassword;
        if (adminUser.isBlank() || adminPassword.isBlank()) {
            throw new IllegalStateException("RM_ADMIN_USER and RM_ADMIN_PASSWORD must both be set.");
        }
        this.hashedAdminPassword = new BCryptPasswordEncoder().encode(adminPassword);
    }

    @PostConstruct
    void loadStoredPassword() {
        try {
            if (resetStoredPassword) {
                adminCredentialRepository.deleteById(AdminCredential.ID);
                logger.warn("RM_ADMIN_PASSWORD_RESET is set: a password changed in the UI was discarded, RM_ADMIN_PASSWORD applies again.");
                return;
            }

            adminCredentialRepository.findById(AdminCredential.ID).ifPresent(stored -> {
                this.hashedAdminPassword = stored.getPasswordHash();
                this.credentialVersion = stored.getUpdated();
            });
        } catch (Exception e) {
            logger.error("Could not read the stored admin password, using RM_ADMIN_PASSWORD: " + e);
        }
    }

    /** Remembers a new password hash (already stored by the caller) and signs out every existing session. */
    public void passwordChanged(String newHash, long version) {
        this.hashedAdminPassword = newHash;
        this.credentialVersion = version;
    }

    public String currentPasswordHash() {
        return hashedAdminPassword;
    }

    public long credentialVersion() {
        return credentialVersion;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (adminUser.equals(username)) {
            return new User(adminUser, hashedAdminPassword, new ArrayList<>());
        }
        // Don't echo the supplied username into logs or messages
        logger.warn("Authentication attempted for unknown user");
        throw new UsernameNotFoundException("User not found");
    }
}
