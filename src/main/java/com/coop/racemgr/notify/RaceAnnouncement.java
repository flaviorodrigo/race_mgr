package com.coop.racemgr.notify;

import com.coop.racemgr.model.Race;
import com.coop.racemgr.utils.RacemgrUtils;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Works out whether a freshly imported race is worth announcing, and what to say about it. */
public final class RaceAnnouncement {
    // Importing history after a restart must not announce old races
    static final long RECENT_SECONDS = 30 * 60;

    private RaceAnnouncement() {}

    public record Prepared(String trackId, List<DiscordNotifier.Result> results) {}

    public static Optional<Prepared> prepare(Race race, long nowEpochSeconds) {
        if (!Boolean.TRUE.equals(race.getFinished()) || race.getStages() == null || race.getEnd_time() == null) {
            return Optional.empty();
        }

        long end;
        try {
            end = Long.parseLong(race.getEnd_time().trim());
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        if (nowEpochSeconds - end > RECENT_SECONDS) {
            return Optional.empty();
        }

        JsonNode results = RacemgrUtils.JSON.valueToTree(race.getStages()).path("race1").path("results");
        if (!results.isArray() || results.isEmpty()) {
            return Optional.empty();
        }

        List<DiscordNotifier.Result> parsed = new ArrayList<>();
        for (JsonNode r : results) {
            JsonNode attributes = r.path("attributes");
            if (r.path("name").isMissingNode() || !attributes.path("RacePosition").isNumber()) {
                continue;
            }
            Long fastest = attributes.path("FastestLapTime").isNumber() ? attributes.path("FastestLapTime").asLong() : null;
            parsed.add(new DiscordNotifier.Result(r.path("name").asString(), attributes.path("RacePosition").asInt(), fastest));
        }
        if (parsed.isEmpty()) {
            return Optional.empty();
        }

        JsonNode trackId = RacemgrUtils.JSON.valueToTree(race.getSetup() == null ? java.util.Map.of() : race.getSetup()).path("TrackId");
        return Optional.of(new Prepared(trackId.isMissingNode() ? null : trackId.asString(), parsed));
    }
}
