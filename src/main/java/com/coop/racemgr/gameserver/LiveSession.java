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

        String stage = text(attributes.path("SessionStage"));
        if (stage == null) {
            stage = text(status.path("state"));
        }
        return new Summary(stage, text(attributes.path("TrackId")), members.size(),
                integer(attributes.path("MaxPlayers")), members);
    }

    /** Only whole, non-negative reference ids are ever sent to the game server. */
    public static boolean isValidRefId(Integer refid) {
        return refid != null && refid >= 0 && refid <= 65535;
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
    static URI kickUri(String baseUrl, int refid) {
        return UriComponentsBuilder.fromUriString(baseUrl + "/session/kick")
                .queryParam("refid", "{refid}").encode().buildAndExpand(refid).toUri();
    }

    static URI chatUri(String baseUrl, String message) {
        return UriComponentsBuilder.fromUriString(baseUrl + "/session/send_chat")
                .queryParam("message", "{message}").encode().buildAndExpand(message).toUri();
    }

    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asString();
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
