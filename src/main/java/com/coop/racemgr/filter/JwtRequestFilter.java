package com.coop.racemgr.filter;

import com.coop.racemgr.security.JwtTokenUtil;
import com.coop.racemgr.security.SessionCookies;
import com.coop.racemgr.service.JwtUserDetailsService;

import java.io.IOException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtRequestFilter extends OncePerRequestFilter {
    @Autowired
    private JwtUserDetailsService jwtUserDetailsService;

    @Autowired
    private JwtTokenUtil jwtTokenUtil;

    @Autowired
    private SecurityContextRepository securityContextRepository;

    // NOTE: Filters are run on all requests, even those that don't require a JWT. When
    // this happens it basically behaves like a passthough.
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        final String requestTokenHeader = request.getHeader("Authorization");

        // API clients send "Authorization: Bearer <token>"; the web UI uses the HttpOnly session cookie
        String jwtToken = null;
        if (requestTokenHeader != null && requestTokenHeader.startsWith("Bearer ")) {
            jwtToken = requestTokenHeader.substring(7);
        } else {
            jwtToken = SessionCookies.tokenFrom(request);
        }

        if (jwtToken != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                // Parsing verifies the signature and expiry; any failure means "not authenticated"
                String username = jwtTokenUtil.getUsernameFromToken(jwtToken);
                UserDetails userDetails = this.jwtUserDetailsService.loadUserByUsername(username);

                if (jwtTokenUtil.validateToken(jwtToken, userDetails, jwtUserDetailsService.credentialVersion())) {
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContext context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authentication);
                    SecurityContextHolder.setContext(context);
                    securityContextRepository.saveContext(context, request, response);
                }
            } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
                // Invalid, tampered, expired or unknown-user token: continue unauthenticated and
                // let the security chain answer 401. Don't log token contents.
                logger.warn("Rejected invalid JWT: " + e.getClass().getSimpleName());
            }
        }

        chain.doFilter(request, response);
    }
}
