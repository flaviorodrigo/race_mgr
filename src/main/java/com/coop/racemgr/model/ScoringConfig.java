package com.coop.racemgr.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * How a race session (a championship) awards points. A session without a scoring config uses the
 * original built-in formula ("default").
 */
@Data
public class ScoringConfig {
    public static final String TYPE_DEFAULT = "default";
    public static final String TYPE_TABLE = "table";

    public static final int MAX_POSITIONS = 100;
    public static final int MAX_POINTS = 1000;

    // "default" = the built-in formula, "table" = points by finishing position
    private String type = TYPE_TABLE;
    // Points for 1st, 2nd, 3rd... Positions beyond the list score nothing.
    private List<Integer> positions = new ArrayList<>();
    private int poleBonus = 0;
    private int fastestLapBonus = 0;

    /** @return a human readable problem, or null when the config is acceptable */
    public String validate() {
        if (!TYPE_DEFAULT.equals(type) && !TYPE_TABLE.equals(type)) {
            return "Unknown scoring type";
        }
        if (TYPE_DEFAULT.equals(type)) {
            return null;
        }
        if (positions == null || positions.isEmpty() || positions.size() > MAX_POSITIONS) {
            return "Scoring needs between 1 and " + MAX_POSITIONS + " position values";
        }
        for (Integer points : positions) {
            if (points == null || points < 0 || points > MAX_POINTS) {
                return "Position points must be between 0 and " + MAX_POINTS;
            }
        }
        if (poleBonus < 0 || poleBonus > MAX_POINTS || fastestLapBonus < 0 || fastestLapBonus > MAX_POINTS) {
            return "Bonus points must be between 0 and " + MAX_POINTS;
        }
        return null;
    }
}
