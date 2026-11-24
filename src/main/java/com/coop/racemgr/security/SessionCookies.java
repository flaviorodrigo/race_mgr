package com.coop.racemgr.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import java.time.Duration;

/**
 * The admin session cookies. The JWT lives in an HttpOnly cookie, so script running in the page
 * (XSS) cannot read it. A second, readable cookie only tells the UI that a session exists.
 * Both are SameSite=Strict, and state-changing requests still need the CSRF token.
 */
public final class SessionCookies {
    public static final String TOKEN = "JWT-TOKEN";
    public static final String INDICATOR = "RM-SESSION";

    private SessionCookies() {}

    public static void add(HttpServletRequest request, HttpServletResponse response, String token, Duration lifetime) {
        write(request, response, TOKEN, token, true, lifetime);
        write(request, response, INDICATOR, "1", false, lifetime);
    }

    public static void clear(HttpServletRequest request, HttpServletResponse response) {
        write(request, response, TOKEN, "", true, Duration.ZERO);
        write(request, response, INDICATOR, "", false, Duration.ZERO);
    }

    public static String tokenFrom(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (TOKEN.equals(cookie.getName()) && !cookie.getValue().isEmpty()) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    /**
     * RM_COOKIE_SECURE=true: Race Manager sits behind a TLS proxy that connects over plain HTTP, so
     * treat every request as HTTPS for cookies (Secure) and send Strict-Transport-Security.
     */
    public static boolean httpsOnly() {
        return "true".equalsIgnoreCase(System.getenv("RM_COOKIE_SECURE"));
    }

    // "Secure" whenever this request arrived over TLS, or always with RM_COOKIE_SECURE=true.
    private static void write(HttpServletRequest request, HttpServletResponse response, String name, String value,
                              boolean httpOnly, Duration maxAge) {
        boolean secure = request.isSecure() || httpsOnly();
        ResponseCookie cookie = ResponseCookie.from(name, value)
                .httpOnly(httpOnly)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}
