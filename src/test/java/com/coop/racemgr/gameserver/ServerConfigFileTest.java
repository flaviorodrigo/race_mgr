package com.coop.racemgr.gameserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServerConfigFileTest {
    // Shaped like a real server.cfg: comments, a dummy "//" entry, look-alike keys in comments and nested blocks
    private static final String CFG = """
            // Main server configuration
            "//" : "name : \\"not this one\\"",
            logLevel : "info"
            // name : "commented out"
            name : "McRod Server", // trailing comment
            secure : true
            password : "secret1"
            maxPlayerCount : 24

            httpApiUsers : {
                "name" : "nested, must be ignored",
                "admin" : "x"
            }
            allowEmptyJoin : false
            sessionAttributes : {
                "GridSize" : 24,
                "MaxPlayers" : 24,
                "PracticeLength" : 10,
                // "RaceLength" : 99,
                "RaceLength" : 5,
                "OpponentDifficulty" : 80,
                "TrackId" : 1988984740
            }
            """;

    private Path write(Path dir, String content) throws IOException {
        Path file = dir.resolve("server.cfg");
        Files.writeString(file, content);
        return file;
    }

    @Test
    void readsTheWhitelistedValuesAndHidesThePassword(@TempDir Path dir) throws Exception {
        Map<String, Object> values = new ServerConfigFile(write(dir, CFG)).read();

        assertThat(values).containsEntry("name", "McRod Server").containsEntry("secure", true).containsEntry("maxPlayerCount", 24)
                .containsEntry("allowEmptyJoin", false).containsEntry("sessionAttributes.GridSize", 24)
                .containsEntry("sessionAttributes.RaceLength", 5).containsEntry("passwordSet", true);
        assertThat(values).doesNotContainKey("password");
        assertThat(values.toString()).doesNotContain("secret1");
    }

    @Test
    void changesOnlyTheNamedValuesAndKeepsEverythingElse(@TempDir Path dir) throws Exception {
        Path file = write(dir, CFG);
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("name", "Friday Night");
        changes.put("maxPlayerCount", 16);
        changes.put("sessionAttributes.RaceLength", 12);
        changes.put("secure", false);
        new ServerConfigFile(file).write(changes);

        String expected = CFG.replace("\"McRod Server\"", "\"Friday Night\"").replace("maxPlayerCount : 24", "maxPlayerCount : 16")
                .replace("\"RaceLength\" : 5,", "\"RaceLength\" : 12,").replace("secure : true", "secure : false");
        assertThat(Files.readString(file)).isEqualTo(expected);
        // The commented-out look-alikes, the nested "name" and the dummy entry are untouched (covered by the equality above)
        assertThat(Files.readString(dir.resolve("server.cfg.bak"))).isEqualTo(CFG);
    }

    @Test
    void escapesQuotesAndBackslashesInText(@TempDir Path dir) throws Exception {
        Path file = write(dir, CFG);
        new ServerConfigFile(file).write(Map.of("password", "pa\"ss\\word"));

        assertThat(Files.readString(file)).contains("password : \"pa\\\"ss\\\\word\"");
        assertThat(new ServerConfigFile(file).read()).containsEntry("passwordSet", true);
        new ServerConfigFile(file).write(Map.of("password", ""));
        assertThat(new ServerConfigFile(file).read()).containsEntry("passwordSet", false);
    }

    @Test
    void rejectsUnknownKeysBadValuesAndInjection() {
        assertThat(ServerConfigFile.validate(Map.of("httpApiUsers", "x"))).contains("not allowed");
        assertThat(ServerConfigFile.validate(Map.of("name", 5))).contains("text");
        assertThat(ServerConfigFile.validate(Map.of("name", "a\nb: 1"))).contains("text");
        assertThat(ServerConfigFile.validate(Map.of("name", "x".repeat(65)))).contains("text");
        assertThat(ServerConfigFile.validate(Map.of("maxPlayerCount", 65))).contains("whole number");
        assertThat(ServerConfigFile.validate(Map.of("maxPlayerCount", "24"))).contains("whole number");
        assertThat(ServerConfigFile.validate(Map.of("secure", "yes"))).contains("true or false");
        assertThat(ServerConfigFile.validate(Map.of("sessionAttributes.RaceLength", 0))).contains("whole number");
        assertThat(ServerConfigFile.validate(Map.of("name", "ok", "maxPlayerCount", 8))).isNull();
        assertThat(ServerConfigFile.validate(Map.of())).isNotNull();
    }

    @Test
    void leavesTheFileAloneWhenAnythingIsWrong(@TempDir Path dir) throws Exception {
        Path file = write(dir, CFG);
        var cfg = new ServerConfigFile(file);

        assertThatThrownBy(() -> cfg.write(Map.of("maxPlayerCount", 999))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(file)).isEqualTo(CFG);

        // A key that isn't in the file is reported, not invented, and nothing is written
        Path bare = write(dir.resolve("..").normalize().resolve(dir.getFileName()), "name : \"x\"\n");
        assertThatThrownBy(() -> new ServerConfigFile(bare).write(Map.of("name", "y", "maxPlayerCount", 8))).isInstanceOf(IOException.class);
        assertThat(Files.readString(bare)).isEqualTo("name : \"x\"\n");
    }

    // The block as it is in a real server.cfg: comments, an entry with a trailing comma, a negative id, a string-valued key elsewhere
    private static final String REAL = """
            name : "McRod AMS2 Server"
            sessionAttributes : {
                "ServerControlsTrack" : 1,
                "GridSize" : 24,
                // Visual only damage
                "DamageType" : 1,
                "RaceWeatherSlot1" : -934211870,
                "RaceWeatherSlot4" : -934211870,
            }
            controlGameSetup : true
            """;

    @Test
    void readsEveryWholeNumberInTheSessionBlock(@TempDir Path dir) throws Exception {
        var values = new ServerConfigFile(write(dir, REAL)).readSessionAttributes();
        assertThat(values).containsExactly(Map.entry("ServerControlsTrack", 1), Map.entry("GridSize", 24), Map.entry("DamageType", 1),
                Map.entry("RaceWeatherSlot1", -934211870), Map.entry("RaceWeatherSlot4", -934211870));
    }

    @Test
    void changesExistingSessionSettingsInPlaceAndAddsMissingOnesAtTheEndOfTheBlock(@TempDir Path dir) throws Exception {
        Path file = write(dir, REAL);
        Map<String, Integer> changes = new LinkedHashMap<>();
        changes.put("DamageType", 3);
        changes.put("ManualPitStops", 1);
        changes.put("PitWhiteLinePenalty", 1);
        new ServerConfigFile(file).writeSessionAttributes(changes);

        String expected = REAL.replace("\"DamageType\" : 1,", "\"DamageType\" : 3,")
                .replace("    \"RaceWeatherSlot4\" : -934211870,\n", "    \"RaceWeatherSlot4\" : -934211870,\n    \"ManualPitStops\" : 1,\n    \"PitWhiteLinePenalty\" : 1,\n");
        assertThat(Files.readString(file)).isEqualTo(expected);
        assertThat(Files.readString(dir.resolve("server.cfg.bak"))).isEqualTo(REAL);
        assertThat(new ServerConfigFile(file).readSessionAttributes()).containsEntry("ManualPitStops", 1).containsEntry("DamageType", 3);
    }

    @Test
    void addsTheCommaThatALastEntryWithoutOneNeeds(@TempDir Path dir) throws Exception {
        String cfg = "sessionAttributes : {\n  \"GridSize\" : 24 // players\n}\nx : 1\n";
        Path file = write(dir, cfg);
        new ServerConfigFile(file).writeSessionAttributes(Map.of("ManualPitStops", 1));
        assertThat(Files.readString(file)).isEqualTo("sessionAttributes : {\n  \"GridSize\" : 24, // players\n  \"ManualPitStops\" : 1,\n}\nx : 1\n");
    }

    @Test
    void refusesUnknownSessionSettingsAndAMissingBlock(@TempDir Path dir) throws Exception {
        Path file = write(dir, REAL);
        assertThatThrownBy(() -> new ServerConfigFile(file).writeSessionAttributes(Map.of("NotASetting", 1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(file)).isEqualTo(REAL);
        Path other = dir.resolve("other.cfg");
        Files.writeString(other, "name : \"x\"\n");
        assertThatThrownBy(() -> new ServerConfigFile(other).writeSessionAttributes(Map.of("ManualPitStops", 1))).isInstanceOf(IOException.class);
    }
}
