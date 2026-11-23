package com.coop.racemgr.controllers;

import javax.servlet.http.HttpServletRequest;

import com.coop.racemgr.RacemgrApplication;
import com.coop.racemgr.security.JwtTokenUtil;
import com.coop.racemgr.security.LoginAttemptService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.coop.racemgr.model.JwtRequest;
import com.coop.racemgr.model.JwtResponse;

@RestController
public class JwtAuthenticationController {
    private static final Logger logger = LogManager.getLogger(RacemgrApplication.class);

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Autowired
    private UserDetailsService jwtUserDetailsService;

    @Autowired
    private LoginAttemptService loginAttemptService;

    @PostMapping("/api/v1/authenticate")
    public ResponseEntity<Object> createAuthenticationToken(@RequestBody JwtRequest authenticationRequest,
                                                            HttpServletRequest httpRequest) {
        // NOTE: Deliberately uses the socket address, not X-Forwarded-For (which a client controls).
        String client = httpRequest.getRemoteAddr();

        if (loginAttemptService.isBlocked(client)) {
            logger.warn("Login temporarily blocked after repeated failures");
            return ResponseHandler.generateResponse("Too many failed attempts, try again later",
                    HttpStatus.TOO_MANY_REQUESTS, null);
        }

        String username = authenticationRequest.getUsername();
        String password = authenticationRequest.getPassword();
        if (username == null || password == null) {
            return ResponseHandler.generateResponse("Username and password are required", HttpStatus.BAD_REQUEST, null);
        }

        try {
            authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, password));
        } catch (AuthenticationException e) {
            loginAttemptService.recordFailure(client);
            logger.warn("Failed login attempt");
            return ResponseHandler.generateResponse("Invalid credentials", HttpStatus.UNAUTHORIZED, null);
        }

        loginAttemptService.recordSuccess(client);
        final UserDetails userDetails = jwtUserDetailsService.loadUserByUsername(username);
        final String token = jwtTokenUtil.generateToken(userDetails);
        return ResponseHandler.generateResponse("JWT token", HttpStatus.OK, new JwtResponse(token));
    }
}
