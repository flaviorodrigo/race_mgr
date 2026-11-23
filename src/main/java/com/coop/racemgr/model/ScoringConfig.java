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

    // The lowest N round scores of each driver don't count (a round they missed counts as 0 and goes first)
    private int dropRounds = 0;
    // Hand-made corrections added to a driver's total after dropped rounds
    private List<Adjustment> adjustments = new ArrayList<>();
    // Optional team standings: a team's points are its drivers' totals added up
    private List<Team> teams = new ArrayList<>();

    @Data
    public static class Adjustment {
        private String driver;
        private int points;
        private String note = "";
    }

    @Data
    public static class Team {
        private String name;
        private List<String> drivers = new ArrayList<>();
    }

    public static final int MAX_DROP_ROUNDS = 50;
    public static final int MAX_ADJUSTMENTS = 200;
    public static final int MAX_TEAMS = 50;
    public static final int MAX_NAME = 64;

    /** True when this is just "the built-in formula" with nothing extra, i.e. the same as no scoring config. */
    public boolean isPlainDefault() {
        return TYPE_DEFAULT.equals(type) && dropRounds == 0
                && (adjustments == null || adjustments.isEmpty()) && (teams == null || teams.isEmpty());
    }

    /** @return a human readable problem, or null when the config is acceptable */
    public String validate() {
        if (!TYPE_DEFAULT.equals(type) && !TYPE_TABLE.equals(type)) {
            return "Unknown scoring type";
        }
        String extras = validateExtras();
        if (extras != null) {
            return extras;
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

    private String validateExtras() {
        if (dropRounds < 0 || dropRounds > MAX_DROP_ROUNDS) {
            return "Dropped rounds must be between 0 and " + MAX_DROP_ROUNDS;
        }
        if (adjustments == null || adjustments.size() > MAX_ADJUSTMENTS) {
            return "Too many point adjustments";
        }
        for (Adjustment a : adjustments) {
            if (!validName(a.getDriver())) {
                return "Each adjustment needs a driver name of up to " + MAX_NAME + " characters";
            }
            if (a.getPoints() < -MAX_POINTS || a.getPoints() > MAX_POINTS) {
                return "Adjustments must be between -" + MAX_POINTS + " and " + MAX_POINTS;
            }
            if (a.getNote() != null && a.getNote().length() > 100) {
                return "Adjustment notes are limited to 100 characters";
            }
        }
        if (teams == null || teams.size() > MAX_TEAMS) {
            return "Too many teams";
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Team t : teams) {
            if (!validName(t.getName()) || t.getDrivers() == null || t.getDrivers().size() > 50) {
                return "Each team needs a name and up to 50 drivers";
            }
            for (String driver : t.getDrivers()) {
                if (!validName(driver)) {
                    return "Driver names in a team can't be empty or longer than " + MAX_NAME + " characters";
                }
                if (!seen.add(driver)) {
                    return "A driver can only be in one team: " + driver.replaceAll("\\p{Cntrl}", " ");
                }
            }
        }
        return null;
    }

    private static boolean validName(String name) {
        return name != null && !name.isBlank() && name.length() <= MAX_NAME;
    }
}
