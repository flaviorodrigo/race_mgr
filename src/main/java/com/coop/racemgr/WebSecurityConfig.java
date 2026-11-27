package com.coop.racemgr;

import com.coop.racemgr.filter.JwtRequestFilter;
import com.coop.racemgr.security.JwtAuthenticationEntryPoint;
import com.coop.racemgr.security.SessionCookies;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// NOTE: This setup is built around a modified version of the example found here:
// https://www.javainuse.com/spring/boot-jwt

@Configuration
@EnableWebSecurity
public class WebSecurityConfig {
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new RequestAttributeSecurityContextRepository();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Uses the UserDetailsService and PasswordEncoder beans above/in service/
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtRequestFilter jwtRequestFilter,
                                           SecurityContextRepository securityContextRepository,
                                           JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint) throws Exception {
        http
            // The SPA reads the XSRF-TOKEN cookie and echoes it in the X-XSRF-TOKEN header, so the
            // plain (non-XOR masked) request handler is used.
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                .ignoringRequestMatchers("/api/v1/race/ingest"))
            .authorizeHttpRequests(auth -> auth
                // Managing the other admins: the owner only
                .requestMatchers("/api/v1/admin/users", "/api/v1/admin/users/**").hasRole("OWNER")
                // Any signed-in person: who am I, change my own password, SimHub ingest token
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/me").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/ingest/token").hasRole("STEWARD")
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/password").authenticated()
                // Race-day actions a steward may do (an admin and the owner may too). Anything not listed here
                // needs the admin role, so a new admin endpoint is admin-only until it is listed.
                .requestMatchers(HttpMethod.GET, "/api/v1/admin/gameserver/session", "/api/v1/admin/gameserver/log",
                        "/api/v1/admin/gameserver/presets", "/api/v1/admin/gameserver/schema", "/api/v1/admin/gameserver/lobby").hasRole("STEWARD")
                .requestMatchers(HttpMethod.POST, "/api/v1/admin/gameserver/kick", "/api/v1/admin/gameserver/chat",
                        "/api/v1/admin/gameserver/session/next", "/api/v1/admin/gameserver/session/advance",
                        "/api/v1/admin/gameserver/presets/*/apply").hasRole("STEWARD")
                .requestMatchers(HttpMethod.PUT, "/api/v1/admin/race/events/*/penalties", "/api/v1/admin/race/events/*/video").hasRole("STEWARD")
                // Everything else under /admin is for admins
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // Public read-only endpoints (also GET /api, which hands out the XSRF token)
                .requestMatchers(HttpMethod.GET, "/api", "/api/v1/gameserver/**", "/api/v1/config", "/api/v1/race/**", "/api/v1/sso").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/authenticate", "/api/v1/logout", "/api/v1/sso/login", "/api/v1/race/ingest").permitAll()
                // Anything else under /api is denied, so a new endpoint is private unless listed above
                .requestMatchers("/api/**").denyAll()
                // Everything else is the React single page app's static assets
                .anyRequest().permitAll())
            .headers(headers -> headers
                // The SPA is self-contained (bundled scripts, fonts and images). Styles need
                // 'unsafe-inline' because the UI library injects <style> tags at runtime.
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; "
                    + "base-uri 'self'; form-action 'self'; frame-ancestors 'none'"))
                .referrerPolicy(referrer -> referrer.policy(
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                // HSTS on HTTPS requests, and on every request behind a TLS proxy (RM_COOKIE_SECURE=true).
                // Browsers ignore it over plain HTTP, so an SSH tunnel to http://localhost keeps working.
                // Only this host name: other apps on the same domain are left alone.
                .httpStrictTransportSecurity(hsts -> hsts
                    .requestMatcher(request -> request.isSecure() || SessionCookies.httpsOnly())
                    .includeSubDomains(false)
                    .maxAgeInSeconds(31536000)))
            .exceptionHandling(e -> e
                .authenticationEntryPoint(jwtAuthenticationEntryPoint)
                // Signed in, but the role isn't enough: say so in the API's usual shape
                .accessDeniedHandler((request, response, denied) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"message\":\"Your role is not allowed to do this\",\"status\":403,\"data\":null}");
                }))
            // Stateless: no server-side session holds the user's state
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // JwtRequestFilter records the authenticated context here for the duration of the request. The
            // stateless default repository never "contains" a context, which makes Spring Security treat
            // every request as a fresh login and rotate (clear) the CSRF token each time.
            .securityContext(sc -> sc.securityContextRepository(securityContextRepository))
            // The CSRF token is loaded lazily, so force it so the cookie is sent with every response
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
            // Validate the JWT on every request
            .addFilterBefore(jwtRequestFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    // The UI must read XSRF-TOKEN, so it isn't HttpOnly. SameSite=Strict, and Secure on HTTPS or
    // behind a TLS proxy (RM_COOKIE_SECURE=true), the same as the session cookies.
    static CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        boolean httpsOnly = SessionCookies.httpsOnly();
        repository.setCookieCustomizer(cookie -> {
            cookie.sameSite("Strict");
            if (httpsOnly) {
                cookie.secure(true);
            }
        });
        return repository;
    }

    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken(); // renders the token, which writes the cookie
            }
            chain.doFilter(request, response);
        }
    }
}
