package com.coop.racemgr.service;

import java.util.ArrayList;

import com.coop.racemgr.RacemgrApplication;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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
    // BCrypt hash of the admin password, computed once at startup
    private final String hashedAdminPassword;

    public JwtUserDetailsService(@Value("${RM_ADMIN_USER:}") String adminUser,
                                 @Value("${RM_ADMIN_PASSWORD:}") String adminPassword) {
        this.adminUser = adminUser;
        if (adminUser.isBlank() || adminPassword.isBlank()) {
            throw new IllegalStateException("RM_ADMIN_USER and RM_ADMIN_PASSWORD must both be set.");
        }
        this.hashedAdminPassword = new BCryptPasswordEncoder().encode(adminPassword);
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
