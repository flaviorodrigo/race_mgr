package com.coop.racemgr.filter;

import com.coop.racemgr.security.JwtTokenUtil;
import com.coop.racemgr.model.AdminRole;
import com.coop.racemgr.security.SessionCookies;
import com.coop.racemgr.security.SynologySso;
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

    @Autowired
    private SynologySso synologySso;

    // NOTE: Filters are run on all requests, even those that don't require a JWT. When
    // this happens it basically behaves like a passthough.
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The web UI uses the HttpOnly session cookie; API clients send "Authorization: Bearer <token>". The cookie
        // wins: behind the Synology sign-in proxy, the Authorization header carries the SSO Server's ID token,
        // which is only read by the Synology sign-in endpoint.
        String cookieToken = SessionCookies.tokenFrom(request);
        String jwtToken = cookieToken;
        final String requestTokenHeader = request.getHeader("Authorization");
        if (jwtToken == null && requestTokenHeader != null && requestTokenHeader.startsWith("Bearer ")) {
            jwtToken = requestTokenHeader.substring(7);
        }

        if (jwtToken != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                // Parsing verifies the signature and expiry; any failure means "not authenticated"
                String username = jwtTokenUtil.getUsernameFromToken(jwtToken);
                UserDetails userDetails = username.startsWith(SynologySso.PREFIX)
                        ? ssoUser(username, jwtTokenUtil.getGroupsFromToken(jwtToken))
                        : this.jwtUserDetailsService.loadUserByUsername(username);

                if (userDetails != null && jwtTokenUtil.validateToken(jwtToken, userDetails, ((JwtUserDetailsService.RmUser) userDetails).getCredentialVersion())) {
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
                // let the security chain answer 401. Don't log token contents. A bearer token that isn't ours
                // is usually the Synology ID token the sign-in proxy adds to every request: not worth a warning.
                if (cookieToken != null) {
                    logger.warn("Rejected invalid JWT: " + e.getClass().getSimpleName());
                } else if (logger.isDebugEnabled()) {
                    logger.debug("Ignored a bearer token that is not a Race Manager session: " + e.getClass().getSimpleName());
                }
            }
        }

        chain.doFilter(request, response);
    }

    // Someone signed in with Synology: their role comes from the current RM_SSO_* settings, so taking them off
    // the list ends their access. No role (any more): no session.
    private UserDetails ssoUser(String sessionName, java.util.List<String> groups) {
        AdminRole role = synologySso.roleFor(sessionName.substring(SynologySso.PREFIX.length()), groups);
        return role == null ? null : new JwtUserDetailsService.RmUser(sessionName, "", role, 0);
    }
}
