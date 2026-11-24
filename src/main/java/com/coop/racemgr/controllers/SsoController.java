package com.coop.racemgr.controllers;

import com.coop.racemgr.RacemgrApplication;
import com.coop.racemgr.audit.AuditLog;
import com.coop.racemgr.model.AdminRole;
import com.coop.racemgr.security.JwtTokenUtil;
import com.coop.racemgr.security.SessionCookies;
import com.coop.racemgr.security.SynologySso;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * "Sign in with Synology": turns the ID token the sign-in proxy forwards into a Race Manager session, for DSM users
 * and groups listed in RM_SSO_ADMINS / RM_SSO_STEWARDS / RM_SSO_*_GROUPS. See {@link SynologySso}.
 */
@RestController
public class SsoController {
    private static final Logger logger = LogManager.getLogger(RacemgrApplication.class);

    @Autowired
    private SynologySso synologySso;

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Autowired
    private AuditLog auditLog;

    /** Whether the login page offers "Sign in with Synology". */
    @GetMapping("/api/v1/sso")
    public ResponseEntity<Object> status() {
        return ResponseHandler.generateResponse("Synology sign-in", HttpStatus.OK, Map.of("enabled", synologySso.isEnabled()));
    }

    @PostMapping("/api/v1/sso/login")
    public ResponseEntity<Object> login(HttpServletRequest request, HttpServletResponse response) {
        if (!synologySso.isEnabled()) {
            return ResponseHandler.generateResponse("Synology sign-in is not set up", HttpStatus.NOT_FOUND, null);
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            // Not behind the sign-in proxy, or it doesn't pass the token on (--pass-authorization-header=true)
            return ResponseHandler.generateResponse("No Synology sign-in came with the request", HttpStatus.UNAUTHORIZED,
                    Map.of("freshSignIn", true));
        }

        SynologySso.Identity who;
        try {
            who = synologySso.verify(header.substring(7));
        } catch (Exception e) {
            // Expired (the SSO Server's tokens are short-lived), not ours, or the SSO Server couldn't be reached
            logger.info("Synology sign-in token not accepted: " + e.getClass().getSimpleName());
            return ResponseHandler.generateResponse("Sign in with Synology again", HttpStatus.UNAUTHORIZED,
                    Map.of("freshSignIn", true));
        }

        String client = request.getRemoteAddr();
        AdminRole role = synologySso.roleFor(who.username(), who.groups());
        if (role == null) {
            auditLog.record(SynologySso.PREFIX + who.username(), "Synology sign-in refused", "no Race Manager role");
            return ResponseHandler.generateResponse("Your Synology account has no Race Manager role. Ask the owner to add it.",
                    HttpStatus.FORBIDDEN, null);
        }

        auditLog.record(SynologySso.PREFIX + who.username(), "login", "with Synology as " + role.name() + " from " + client);
        SessionCookies.add(request, response, jwtTokenUtil.generateSsoToken(who.username(), who.groups()), jwtTokenUtil.getValidity());
        return ResponseHandler.generateResponse("Signed in", HttpStatus.OK, Map.of("username", who.username(), "role", role.name()));
    }
}
