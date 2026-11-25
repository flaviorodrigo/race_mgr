package com.coop.racemgr.gameserver;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The game server's live session (who is connected, what stage it is in), reduced to the few fields
 * Race Manager shows. Everything is read defensively: the dedicated server's JSON varies a little between
 * versions and sessions, and a missing field must never break the page.
 */
public final class LiveSession {
    public static final int MAX_CHAT_LENGTH = 200;

    private LiveSession() {}

    /** A connected player. refid/steamid are only included in the admin view. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Member(Integer refid, String steamid, String name, Integer ping) {}

    public record Summary(String stage, String trackId, int players, Integer maxPlayers, List<Member> members) {}

    /** @param includeIds true for admins (refid is needed to kick; steamid identifies the person) */
    public static Summary summarize(JsonNode status, boolean includeIds) {
        JsonNode attributes = status.path("attributes");

        List<Member> members = new ArrayList<>();
        JsonNode memberNodes = status.path("members");
        if (memberNodes.isArray()) {
            for (JsonNode m : memberNodes) {
                String name = text(m.path("name"));
                if (name == null) {
                    continue;
                }
                Integer ping = integer(m.path("attributes").path("Ping"));
                if (ping == null) {
                    ping = integer(m.path("ping"));
                }
                members.add(new Member(
                        includeIds ? integer(m.path("refid")) : null,
                        includeIds ? text(m.path("steamid")) : null,
                        name,
                        ping));
            }
        }

        // SessionStage is empty while the server is idle; its overall state (Idle/Allocating/Running) still says something
        String stage = text(attributes.path("SessionStage"));
        if (stage == null) {
            stage = text(status.path("state"));
        }

        // An idle server reports 0 for its sizes: treat that as "unknown" rather than "0 players allowed"
        Integer maxPlayers = positive(integer(status.path("max_member_count")));
        if (maxPlayers == null) {
            maxPlayers = positive(integer(attributes.path("MaxPlayers")));
        }
        return new Summary(stage, nonZero(integer(attributes.path("TrackId"))) == null ? null : text(attributes.path("TrackId")),
                members.size(), maxPlayers, members);
    }

    /** Only whole, non-negative reference ids are ever sent to the game server. */
    public static boolean isValidRefId(Integer refid) {
        return refid != null && refid >= 0 && refid <= 65535;
    }

    public static final int MAX_BAN_SECONDS = 365 * 24 * 60 * 60;

    /** A temporary ban length in seconds: absent means "just kick". */
    public static boolean isValidBan(Integer banSeconds) {
        return banSeconds == null || (banSeconds >= 1 && banSeconds <= MAX_BAN_SECONDS);
    }

    /** Chat is plain text: no control characters, bounded length. */
    public static String cleanChatMessage(String message) {
        if (message == null) {
            return null;
        }
        String cleaned = message.replaceAll("\\p{Cntrl}", " ").trim();
        return cleaned.isEmpty() || cleaned.length() > MAX_CHAT_LENGTH ? null : cleaned;
    }

    // Query values are strictly encoded, so a chat message containing "&" or "=" can't add parameters
    static URI kickUri(String baseUrl, int refid, Integer banSeconds) {
        var builder = UriComponentsBuilder.fromUriString(baseUrl + "/session/kick").queryParam("refid", "{refid}");
        if (banSeconds == null) {
            return builder.encode().buildAndExpand(refid).toUri();
        }
        return builder.queryParam("ban", "{ban}").encode().buildAndExpand(refid, banSeconds).toUri();
    }

    static URI chatUri(String baseUrl, String message) {
        return UriComponentsBuilder.fromUriString(baseUrl + "/session/send_chat")
                .queryParam("message", "{message}").encode().buildAndExpand(message).toUri();
    }

    private static Integer positive(Integer value) {
        return value != null && value > 0 ? value : null;
    }

    // Track and class ids are signed 32-bit numbers (about half of them are negative); 0 means "none"
    private static Integer nonZero(Integer value) {
        return value != null && value != 0 ? value : null;
    }

    // Blank counts as missing
    private static String text(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asString();
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer integer(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.asInt();
        }
        try {
            return Integer.valueOf(node.asString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
