package com.coop.racemgr.model;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * What a signed-in person may do. Each role includes the ones below it:
 * <ul>
 *   <li>STEWARD: race day only: kick and message players, next-session settings and presets, penalties and video
 *       links, the live view and the game server log.</li>
 *   <li>ADMIN: everything about the server and the championships (settings, rotations, ban list, scoring...).</li>
 *   <li>OWNER: also manages the other admins. This is the account from RM_ADMIN_USER; there is only one.</li>
 * </ul>
 * The rules are enforced per URL in WebSecurityConfig: a new admin endpoint needs ADMIN unless it is listed there.
 */
public enum AdminRole {
    STEWARD, ADMIN, OWNER;

    /** Roles an owner can hand out. */
    public boolean isAssignable() {
        return this != OWNER;
    }

    public List<GrantedAuthority> authorities() {
        return java.util.Arrays.stream(values())
                .filter(r -> r.ordinal() <= ordinal())
                .<GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r.name()))
                .toList();
    }

    /** The role named by the text (any case), or null for anything else. */
    public static AdminRole parse(String text) {
        if (text == null) {
            return null;
        }
        try {
            return valueOf(text.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
