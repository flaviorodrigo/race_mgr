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

    @Test
    void addsAndRemovesSteamIdsKeepingEverythingElse(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("blacklist.cfg");
        Files.writeString(file, "// banned drivers\n76561198000000001 // cutting corners\n\n# old note\n");
        var list = new BlackList(file);

        list.add("76561198000000002", "rammed\nsomeone");
        list.add("76561198000000002", "again");
        assertThat(list.list()).extracting(BlackList.Entry::steamId).containsExactly("76561198000000001", "76561198000000002");
        assertThat(list.list().get(1).note()).isEqualTo("rammed someone");

        assertThat(list.remove("76561198000000001")).isTrue();
        assertThat(list.remove("76561198000000009")).isFalse();
        assertThat(Files.readAllLines(file)).contains("// banned drivers", "# old note").noneMatch(l -> l.startsWith("76561198000000001"));
    }

    @Test
    void refusesToTouchAFormatItDoesNotUnderstand(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("blacklist.cfg");
        String original = "[ \"76561198000000001\", \"76561198000000002\" ]\n";
        Files.writeString(file, original);
        var list = new BlackList(file);

        assertThatThrownBy(list::list).isInstanceOf(BlackList.UnsupportedFormatException.class);
        assertThatThrownBy(() -> list.add("76561198000000003", "")).isInstanceOf(BlackList.UnsupportedFormatException.class);
        assertThat(Files.readString(file)).isEqualTo(original);
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
        assertThat(Files.readAllLines(dir.resolve("blacklist.cfg"))).containsExactly("76561198000000001");
    }
}
