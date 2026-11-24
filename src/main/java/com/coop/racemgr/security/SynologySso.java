package com.coop.racemgr.security;

import com.coop.racemgr.model.AdminRole;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sign-in with the Synology SSO Server (OpenID Connect). oauth2-proxy in front of Race Manager signs the person in
 * and forwards their ID token ("Authorization: Bearer ..."); this checks the token's signature against the SSO
 * Server's published keys, its issuer, audience (our client id) and expiry, then maps the DSM user name or groups
 * to a Race Manager role:
 * <pre>
 *   RM_SSO_ISSUER=https://nas.example.synology.me:4931/webman/sso
 *   RM_SSO_CLIENT_ID=...                      (the application id registered in SSO Server)
 *   RM_SSO_ADMINS=alice                       (DSM user names, comma separated)
 *   RM_SSO_STEWARDS=bob,carol
 *   RM_SSO_ADMIN_GROUPS=racemgr-admins        (DSM groups, if the token carries them)
 *   RM_SSO_STEWARD_GROUPS=racemgr-stewards
 * </pre>
 * The role is worked out again on every request from the current settings, so taking someone off the list
 * (and recreating the container) ends their access. The owner is never an SSO user: that stays RM_ADMIN_USER.
 */
@Component
public class SynologySso {
    /** Race Manager session names for SSO users. Local user names can't contain ':', so they never collide. */
    public static final String PREFIX = "synology:";

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_.@-]{1,64}$");
    private static final int MAX_GROUPS = 200;

    public record Identity(String username, List<String> groups) {}

    private final String issuer;
    private final String clientId;
    private final Set<String> admins;
    private final Set<String> stewards;
    private final Set<String> adminGroups;
    private final Set<String> stewardGroups;
    private volatile JwtDecoder decoder;

    @Autowired
    public SynologySso(@Value("${RM_SSO_ISSUER:}") String issuer,
                       @Value("${RM_SSO_CLIENT_ID:}") String clientId,
                       @Value("${RM_SSO_ADMINS:}") String admins,
                       @Value("${RM_SSO_STEWARDS:}") String stewards,
                       @Value("${RM_SSO_ADMIN_GROUPS:}") String adminGroups,
                       @Value("${RM_SSO_STEWARD_GROUPS:}") String stewardGroups) {
        this(issuer, clientId, admins, stewards, adminGroups, stewardGroups, null);
    }

    // For tests: a decoder that trusts a local key
    SynologySso(String issuer, String clientId, String admins, String stewards, String adminGroups, String stewardGroups,
                JwtDecoder decoder) {
        this.issuer = issuer.trim();
        this.clientId = clientId.trim();
        this.admins = list(admins);
        this.stewards = list(stewards);
        this.adminGroups = list(adminGroups);
        this.stewardGroups = list(stewardGroups);
        this.decoder = decoder;
    }

    public boolean isEnabled() {
        return !issuer.isEmpty() && !clientId.isEmpty();
    }

    /**
     * The person an ID token belongs to. Throws JwtException when the token isn't a valid, unexpired token from
     * our SSO Server for our application (the caller then asks for a fresh sign-in).
     */
    public Identity verify(String idToken) {
        if (!isEnabled()) {
            throw new IllegalStateException("Synology sign-in is not set up");
        }
        Jwt jwt = decoder().decode(idToken);
        String username = jwt.getClaimAsString("username");
        if (username == null || username.isBlank()) {
            username = jwt.getSubject();
        }
        if (username == null || !USERNAME.matcher(username).matches()) {
            throw new JwtException("The sign-in token has no usable user name");
        }
        List<String> groups = jwt.hasClaim("groups") ? jwt.getClaimAsStringList("groups") : List.of();
        if (groups == null) {
            groups = List.of();
        }
        return new Identity(username, groups.stream().filter(g -> g != null && !g.isBlank()).limit(MAX_GROUPS).toList());
    }

    /** The role from the current settings, or null when the person has none (they can only look). */
    public AdminRole roleFor(String username, Collection<String> groups) {
        if (!isEnabled() || username == null) {
            return null;
        }
        String name = username.toLowerCase(Locale.ROOT);
        Set<String> theirGroups = groups == null ? Set.of() : groups.stream()
                .flatMap(g -> {
                    // "racemgr-admins" and "racemgr-admins@domain" both match "racemgr-admins"
                    String lower = g.toLowerCase(Locale.ROOT);
                    int at = lower.indexOf('@');
                    return at > 0 ? java.util.stream.Stream.of(lower, lower.substring(0, at)) : java.util.stream.Stream.of(lower);
                })
                .collect(Collectors.toSet());
        if (admins.contains(name) || theirGroups.stream().anyMatch(adminGroups::contains)) {
            return AdminRole.ADMIN;
        }
        if (stewards.contains(name) || theirGroups.stream().anyMatch(stewardGroups::contains)) {
            return AdminRole.STEWARD;
        }
        return null;
    }

    private JwtDecoder decoder() {
        JwtDecoder current = decoder;
        if (current == null) {
            synchronized (this) {
                if (decoder == null) {
                    // Reads the SSO Server's /.well-known/openid-configuration (which must name the same issuer)
                    // and its key set; failures are retried on the next sign-in.
                    NimbusJwtDecoder built = (NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(issuer);
                    built.setJwtValidator(validator(issuer, clientId));
                    decoder = built;
                }
                current = decoder;
            }
        }
        return current;
    }

    /** Expiry (with a minute of clock skew), issuer, and our application in the audience. */
    static OAuth2TokenValidator<Jwt> validator(String issuer, String clientId) {
        OAuth2TokenValidator<Jwt> audience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(clientId));
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audience);
    }

    private static Set<String> list(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }
}
