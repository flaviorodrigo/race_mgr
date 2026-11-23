package com.coop.racemgr.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.function.Function;

@Component
public class JwtTokenUtil {
    // Minimum secret length (characters). HMAC keys shorter than 256 bits are rejected anyway.
    private static final int MIN_SECRET_LENGTH = 32;
    private static final long DEFAULT_VALIDITY_HOURS = 12;

    private static final String CREDENTIAL_VERSION = "cv";

    private final SecretKey key;
    private final long validityMillis;

    public JwtTokenUtil(@Value("${RM_JWT_SECRET:}") String secret,
                        @Value("${RM_JWT_VALIDITY_HOURS:}") String configured) {
        if (secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException("RM_JWT_SECRET must be set to a random string of at least "
                    + MIN_SECRET_LENGTH + " characters (64 recommended).");
        }
        // The secret is used as raw UTF-8 bytes. (jjwt 0.9 base64-decoded it, so tokens issued
        // before the upgrade are invalid and admins must log in again.)
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));

        long hours = DEFAULT_VALIDITY_HOURS;
        if (!configured.isBlank()) {
            try {
                hours = Math.max(1, Long.parseLong(configured.trim()));
            } catch (NumberFormatException e) {
                throw new IllegalStateException("RM_JWT_VALIDITY_HOURS must be a whole number of hours.");
            }
        }
        this.validityMillis = hours * 60 * 60 * 1000;
    }

    public java.time.Duration getValidity() {
        return java.time.Duration.ofMillis(validityMillis);
    }

    // Retrieve username from jwt token. Throws io.jsonwebtoken.JwtException (or
    // IllegalArgumentException) when the token is invalid, tampered with, or expired.
    public String getUsernameFromToken(String token) {
        return getClaimFromToken(token, Claims::getSubject);
    }

    public <T> T getClaimFromToken(String token, Function<Claims, T> claimsResolver) {
        return claimsResolver.apply(getAllClaimsFromToken(token));
    }

    // The parser verifies the signature and the expiry date.
    private Claims getAllClaimsFromToken(String token) {
        return Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
    }

    // Generate token for user
    public String generateToken(UserDetails userDetails, long credentialVersion) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userDetails.getUsername())
                .claim(CREDENTIAL_VERSION, credentialVersion)
                .issuedAt(new Date(now))
                .expiration(new Date(now + validityMillis))
                .signWith(key) // algorithm (HS256/384/512) chosen from the key length
                .compact();
    }

    // Validate token against the user it claims to belong to, and the password it was issued under
    public Boolean validateToken(String token, UserDetails userDetails, long credentialVersion) {
        Number issuedUnder = getClaimFromToken(token, c -> c.get(CREDENTIAL_VERSION, Number.class));
        long version = issuedUnder == null ? 0 : issuedUnder.longValue();
        return getUsernameFromToken(token).equals(userDetails.getUsername()) && version == credentialVersion;
    }
}
