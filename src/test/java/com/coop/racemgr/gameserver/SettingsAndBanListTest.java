package com.coop.racemgr.gameserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettingsAndBanListTest {
    @Test
    void acceptsOnlyKnownSettingsInRange() {
        assertThat(SessionSettings.validate(Map.of("RaceLength", 10, "TrackId", -559709709))).isNull();
        assertThat(SessionSettings.validate(Map.of())).isNotNull();
        assertThat(SessionSettings.validate(Map.of("RaceLength", 0))).contains("out of range");
        assertThat(SessionSettings.validate(Map.of("OpponentDifficulty", 101))).contains("out of range");
        assertThat(SessionSettings.validate(Map.of("RaceDateHour", 24))).contains("out of range");
        assertThat(SessionSettings.validate(Map.of("ServerControlsSetup", 1))).contains("not allowed");
        // Names are never echoed back verbatim
        assertThat(SessionSettings.validate(Map.of("Race&Length=1", 1))).doesNotContain("&").doesNotContain("=");
    }

    @Test
    void buildsTheSessionPrefixedQuery() {
        Map<String, Integer> settings = new LinkedHashMap<>();
        settings.put("TrackId", -559709709);
        settings.put("RaceLength", 12);
        var uri = SessionSettings.setNextUri("http://127.0.0.1:9000/api", settings);
        assertThat(uri.toString()).isEqualTo("http://127.0.0.1:9000/api/session/set_next_attributes?session_TrackId=-559709709&session_RaceLength=12");
        assertThat(SessionSettings.advanceUri("http://127.0.0.1:9000/api").toString()).isEqualTo("http://127.0.0.1:9000/api/session/advance");
    }

    // The server's own sample (config_sample/blacklist.cfg), byte for byte
    private static final String SAMPLE = """
            // This is a sample blacklist used by sample server config server_with_lists.cfg
            // Here it's just a simple array of Steam IDs.
            // Note that 1234567890 is not a valid Steam ID of any user, so this won't blacklist anyone.

            [
            1234567890
            ]
            """;

    @Test
    void readsAndEditsTheServersOwnSampleFormat(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("blacklist.cfg");
        Files.writeString(file, SAMPLE);
        var list = new BlackList(file);

        assertThat(list.list()).extracting(BlackList.Entry::steamId).containsExactly("1234567890");

        list.add("76561198000000002", "rammed\nsomeone");
        list.add("76561198000000002", "again");
        assertThat(list.list()).extracting(BlackList.Entry::steamId).containsExactly("1234567890", "76561198000000002");
        assertThat(list.list().get(1).note()).isEqualTo("rammed someone");
        // The new entry sits inside the brackets, after the existing one, and the header comments are intact
        assertThat(Files.readString(file)).isEqualTo(SAMPLE.replace("1234567890\n]", "1234567890\n76561198000000002 // rammed someone\n]"));

        assertThat(list.remove("1234567890")).isTrue();
        assertThat(list.remove("76561198000000009")).isFalse();
        assertThat(Files.readString(file)).isEqualTo(SAMPLE.replace("1234567890\n]", "76561198000000002 // rammed someone\n]"));
    }

    @Test
    void keepsCommaAndQuoteStylesConsistent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("blacklist.cfg");
        Files.writeString(file, "[\n\"76561198000000001\", // first\n\"76561198000000002\"\n]\n");
        var list = new BlackList(file);

        list.add("76561198000000003", "");
        assertThat(Files.readAllLines(file)).containsExactly("[", "\"76561198000000001\", // first", "\"76561198000000002\",", "\"76561198000000003\"", "]");
        list.remove("76561198000000003");
        assertThat(Files.readAllLines(file)).containsExactly("[", "\"76561198000000001\", // first", "\"76561198000000002\"", "]");
    }

    @Test
    void refusesToTouchALayoutItDoesNotUnderstand(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("blacklist.cfg");
        for (String original : new String[]{"[ 76561198000000001, 76561198000000002 ]\n", "{ \"ids\": [1] }\n", "76561198000000001\n"}) {
            Files.writeString(file, original);
            var list = new BlackList(file);
            assertThatThrownBy(list::list).isInstanceOf(BlackList.UnsupportedFormatException.class);
            assertThatThrownBy(() -> list.add("76561198000000003", "")).isInstanceOf(BlackList.UnsupportedFormatException.class);
            assertThat(Files.readString(file)).isEqualTo(original);
        }
    }

    @Test
    void listedIdsIncludeTheServersShortPlaceholder() {
        assertThat(BlackList.isListedId("1234567890")).isTrue();
        assertThat(BlackList.isListedId("76561198000000001")).isTrue();
        assertThat(BlackList.isListedId("1234")).isFalse();
        assertThat(BlackList.isListedId("../../x")).isFalse();
    }

    @Test
    void validatesSteamIds(@TempDir Path dir) {
        assertThat(BlackList.isSteamId("76561198000000001")).isTrue();
        for (String bad : List.of("", "1234", "7656119800000000a", "76561198000000001 ", "../etc/passwd", "76561198000000001\n")) {
            assertThat(BlackList.isSteamId(bad)).as(bad).isFalse();
        }
        assertThatThrownBy(() -> new BlackList(dir.resolve("b.cfg")).add("nope", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingFileIsAnEmptyListAndGetsCreated(@TempDir Path dir) throws Exception {
        var list = new BlackList(dir.resolve("blacklist.cfg"));
        assertThat(list.list()).isEmpty();
        list.add("76561198000000001", "");
        assertThat(Files.readAllLines(dir.resolve("blacklist.cfg"))).containsExactly("[", "76561198000000001", "]");
    }
}
