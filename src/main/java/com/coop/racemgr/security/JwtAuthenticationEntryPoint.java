package com.coop.racemgr.security;

import java.io.IOException;
import java.io.Serializable;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.coop.racemgr.RacemgrApplication;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint, Serializable {
    private static final long serialVersionUID = -7858869558953243875L;
    private static final Logger logger = LogManager.getLogger(RacemgrApplication.class);

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        // Warn only: anyone can trigger this, so no stack trace per request
        logger.warn("Unauthorized request");
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
    }
}
