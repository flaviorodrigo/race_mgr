package com.coop.racemgr.service;

import com.coop.racemgr.RacemgrApplication;
import com.coop.racemgr.model.AdminCredential;
import com.coop.racemgr.model.AdminRole;
import com.coop.racemgr.model.AdminUser;
import com.coop.racemgr.repositories.AdminCredentialRepository;
import com.coop.racemgr.repositories.AdminUserRepository;
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

import java.util.Locale;

/**
 * The people who can sign in: the owner (RM_ADMIN_USER, whose password can be changed in the UI) and the extra
 * admins and stewards the owner adds. Roles and credential versions are read on every request, so removing someone
 * or changing their role or password takes effect at once.
 */
@Service
public class JwtUserDetailsService implements UserDetailsService {
    private static final Logger logger = LogManager.getLogger(RacemgrApplication.class);

    /** A signed-in person with their role and the credential version their sessions must carry. */
    public static class RmUser extends User {
        private final AdminRole role;
        private final long credentialVersion;

        public RmUser(String username, String passwordHash, AdminRole role, long credentialVersion) {
            super(username, passwordHash, role.authorities());
            this.role = role;
            this.credentialVersion = credentialVersion;
        }

        public AdminRole getRole() {
            return role;
        }

        public long getCredentialVersion() {
            return credentialVersion;
        }
    }

    private final String adminUser;
    private final boolean resetStoredPassword;

    // BCrypt hash of the owner's password. Starts as the RM_ADMIN_PASSWORD hash, replaced by the stored one
    // when the password has been changed in the UI.
    private volatile String hashedAdminPassword;
    // Changes whenever the owner's password does; tokens carry it so a password change signs every session out
    private volatile long credentialVersion = 0;

    @Autowired
    private AdminCredentialRepository adminCredentialRepository;

    @Autowired
    private AdminUserRepository adminUserRepository;

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

    public String ownerName() {
        return adminUser;
    }

    public boolean isOwner(String username) {
        return adminUser.equals(username);
    }

    /** Stores a new password hash for the person and signs all their existing sessions out. */
    public synchronized void changePassword(String username, String newHash) {
        if (isOwner(username)) {
            long version = Math.max(System.currentTimeMillis(), credentialVersion + 1);
            var credential = new AdminCredential();
            credential.setPasswordHash(newHash);
            credential.setUpdated(version);
            adminCredentialRepository.save(credential);
            this.hashedAdminPassword = newHash;
            this.credentialVersion = version;
            return;
        }

        AdminUser user = adminUserRepository.findByUsername(username.toLowerCase(Locale.ROOT));
        if (user == null) {
            throw new UsernameNotFoundException("User not found");
        }
        setPassword(user, newHash);
        adminUserRepository.save(user);
    }

    /** Sets the hash and a new credential version on a stored user (the caller saves it). */
    public static void setPassword(AdminUser user, String newHash) {
        user.setPasswordHash(newHash);
        user.setCredentialVersion(Math.max(System.currentTimeMillis(), user.getCredentialVersion() + 1));
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if (adminUser.equals(username)) {
            return new RmUser(adminUser, hashedAdminPassword, AdminRole.OWNER, credentialVersion);
        }

        if (username != null && !username.isBlank()) {
            try {
                AdminUser user = adminUserRepository.findByUsername(username.toLowerCase(Locale.ROOT));
                AdminRole role = user == null ? null : AdminRole.parse(user.getRole());
                if (user != null && role != null && role.isAssignable()) {
                    return new RmUser(user.getUsername(), user.getPasswordHash(), role, user.getCredentialVersion());
                }
            } catch (Exception e) {
                // Fail closed: nobody but the owner can sign in while the database can't be read
                logger.error("Could not look up an admin user: " + e);
            }
        }

        // Don't echo the supplied username into logs or messages
        logger.warn("Authentication attempted for unknown user");
        throw new UsernameNotFoundException("User not found");
    }
}
