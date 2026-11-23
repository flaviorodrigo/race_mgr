package com.coop.racemgr;

import com.coop.racemgr.filter.JwtRequestFilter;
import com.coop.racemgr.security.JwtAuthenticationEntryPoint;
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
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .authorizeHttpRequests(auth -> auth
                // All admin paths require authentication
                .requestMatchers("/api/v1/admin/**").authenticated()
                // Public read-only endpoints (also GET /api, which hands out the XSRF token)
                .requestMatchers(HttpMethod.GET, "/api", "/api/v1/gameserver/**", "/api/v1/config", "/api/v1/race/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/authenticate", "/api/v1/logout").permitAll()
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
                    org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN)))
            .exceptionHandling(e -> e.authenticationEntryPoint(jwtAuthenticationEntryPoint))
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
