package com.coop.racemgr.gameserver;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Live timing: the cars of the running session in race order, with their lap and sector times, reduced to what a
 * timing screen shows. Public, so it carries names only (no reference or Steam ids). Read defensively like
 * {@link LiveSession}: a missing field is simply left out.
 */
public final class LiveTiming {
    private LiveTiming() {}

    /** Times are in milliseconds, null while a car has not set one yet. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Car(Integer position, String name, boolean player, Integer lap, Integer sector, Integer s1, Integer s2, Integer s3,
                      Integer lastLap, Integer bestLap, String state, Integer vehicleId) {}

    public record Board(String stage, String trackId, Integer raceLength, List<Car> cars) {}

    public static Board summarize(JsonNode status) {
        JsonNode attributes = status.path("attributes");

        List<Car> cars = new ArrayList<>();
        JsonNode participants = status.path("participants");
        if (participants.isArray()) {
            for (JsonNode p : participants) {
                JsonNode a = p.path("attributes");
                String name = text(a.path("Name"));
                if (name == null) {
                    continue;
                }
                cars.add(new Car(
                        positive(integer(a.path("RacePosition"))),
                        name,
                        integer(a.path("IsPlayer")) != null && integer(a.path("IsPlayer")) != 0,
                        positive(integer(a.path("CurrentLap"))),
                        positive(integer(a.path("CurrentSector"))),
                        positive(integer(a.path("Sector1Time"))),
                        positive(integer(a.path("Sector2Time"))),
                        positive(integer(a.path("Sector3Time"))),
                        positive(integer(a.path("LastLapTime"))),
                        positive(integer(a.path("FastestLapTime"))),
                        text(a.path("State")),
                        integer(a.path("VehicleId"))));
            }
        }
        // Race order; cars without a position yet go last
        cars.sort(Comparator.comparing((Car c) -> c.position() == null ? Integer.MAX_VALUE : c.position()).thenComparing(Car::name));

        String stage = text(attributes.path("SessionStage"));
        if (stage == null) {
            stage = text(status.path("state"));
        }
        Integer track = positive(integer(attributes.path("TrackId")));
        return new Board(stage, track == null ? null : text(attributes.path("TrackId")), positive(integer(attributes.path("RaceLength"))), cars);
    }

    private static Integer positive(Integer value) {
        return value != null && value > 0 ? value : null;
    }

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
