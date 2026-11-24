package com.coop.racemgr.controllers;

import com.coop.racemgr.audit.AuditLog;
import com.coop.racemgr.security.JwtTokenUtil;
import com.coop.racemgr.security.LoginAttemptService;
import com.coop.racemgr.security.SessionCookies;
import com.coop.racemgr.service.JwtUserDetailsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Data;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AccountController {
    public static final int MIN_PASSWORD_LENGTH = 12;
    public static final int MAX_PASSWORD_LENGTH = 128;

    private static final Logger logger = LogManager.getLogger(AccountController.class);

    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtUserDetailsService userDetailsService;
    @Autowired
    private JwtTokenUtil jwtTokenUtil;
    @Autowired
    private LoginAttemptService loginAttemptService;
    @Autowired
    private AuditLog auditLog;

    @Data
    public static class ChangePasswordRequest {
        private String currentPassword;
        private String newPassword;
    }

    /** ADMIN ENDPOINTS **/

    // Changing the password signs every other session out; this one gets a fresh cookie.
    @PostMapping("/api/v1/admin/password")
    public ResponseEntity<Object> changePassword(@RequestBody ChangePasswordRequest request, Authentication authentication,
                                                 HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        // Guessing the current password through this form is rate limited like logging in
        String client = "password:" + httpRequest.getRemoteAddr();
        if (loginAttemptService.isBlocked(client)) {
            return ResponseHandler.generateResponse("Too many failed attempts, try again later", HttpStatus.TOO_MANY_REQUESTS, null);
        }

        String current = request.getCurrentPassword();
        String next = request.getNewPassword();
        if (current == null || next == null) {
            return ResponseHandler.generateResponse("Current and new password are required", HttpStatus.BAD_REQUEST, null);
        }
        if (next.length() < MIN_PASSWORD_LENGTH || next.length() > MAX_PASSWORD_LENGTH) {
            return ResponseHandler.generateResponse("The new password must be " + MIN_PASSWORD_LENGTH + " to "
                    + MAX_PASSWORD_LENGTH + " characters", HttpStatus.BAD_REQUEST, null);
        }
        if (authentication.getName().startsWith(com.coop.racemgr.security.SynologySso.PREFIX)) {
            return ResponseHandler.generateResponse("You signed in with Synology: change your password in DSM", HttpStatus.BAD_REQUEST, null);
        }
        // Every signed-in person changes their own password
        JwtUserDetailsService.RmUser me;
        try {
            me = (JwtUserDetailsService.RmUser) userDetailsService.loadUserByUsername(authentication.getName());
        } catch (UsernameNotFoundException e) {
            return ResponseHandler.generateResponse("Not signed in", HttpStatus.UNAUTHORIZED, null);
        }
        if (!passwordEncoder.matches(current, me.getPassword())) {
            loginAttemptService.recordFailure(client);
            auditLog.record(authentication.getName(), "password change refused", "wrong current password");
            return ResponseHandler.generateResponse("The current password is wrong", HttpStatus.FORBIDDEN, null);
        }
        if (current.equals(next)) {
            return ResponseHandler.generateResponse("Choose a different password", HttpStatus.BAD_REQUEST, null);
        }

        try {
            userDetailsService.changePassword(me.getUsername(), passwordEncoder.encode(next));
            loginAttemptService.recordSuccess(client);

            var userDetails = (JwtUserDetailsService.RmUser) userDetailsService.loadUserByUsername(authentication.getName());
            SessionCookies.add(httpRequest, httpResponse, jwtTokenUtil.generateToken(userDetails, userDetails.getCredentialVersion()),
                    jwtTokenUtil.getValidity());
            auditLog.record(authentication.getName(), "password changed", "all other sessions signed out");
            return ResponseHandler.generateResponse("Password changed", HttpStatus.OK, null);
        } catch (Exception e) {
            logger.error("Password change failed: " + e);
            return ResponseHandler.generateResponse("Request failed", HttpStatus.MULTI_STATUS, null);
        }
    }
}
