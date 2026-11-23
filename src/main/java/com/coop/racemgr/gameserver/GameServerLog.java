package com.coop.racemgr.gameserver;

import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The game server's event log (/api/log/range), newest first, in a shape that is safe to show. */
public final class GameServerLog {
    public static final int MAX_EVENTS = 500;
    private static final int MAX_VALUE_LENGTH = 200;

    private GameServerLog() {}

    public record Event(Long index, Long time, String name, Integer refid, Integer participantid, Map<String, String> attributes) {}

    public static int clampCount(Integer requested) {
        return requested == null ? 100 : Math.max(1, Math.min(MAX_EVENTS, requested));
    }

    public static List<Event> latestFirst(JsonNode response) {
        List<Event> events = new ArrayList<>();
        JsonNode nodes = response.path("events");
        if (nodes.isArray()) {
            for (JsonNode e : nodes) {
                String name = e.path("name").isString() ? e.path("name").asString() : null;
                if (name == null) {
                    continue;
                }
                Map<String, String> attributes = new LinkedHashMap<>();
                JsonNode attrs = e.path("attributes");
                if (attrs.isObject()) {
                    for (var field : attrs.properties()) {
                        String value = field.getValue().isValueNode() ? field.getValue().asString() : field.getValue().toString();
                        attributes.put(field.getKey(), value.length() > MAX_VALUE_LENGTH ? value.substring(0, MAX_VALUE_LENGTH) + "..." : value);
                    }
                }
                events.add(new Event(
                        e.path("index").isNumber() ? e.path("index").asLong() : null,
                        e.path("time").isNumber() ? e.path("time").asLong() : null,
                        name,
                        e.path("refid").isNumber() ? e.path("refid").asInt() : null,
                        e.path("participantid").isNumber() ? e.path("participantid").asInt() : null,
                        attributes));
            }
        }
        Collections.reverse(events);
        return events;
    }
}
