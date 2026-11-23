package com.coop.racemgr.gameserver;

import com.coop.racemgr.utils.RacemgrUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LiveTimingAndLogTest {
    private static final String STATUS = """
            {"state":"Running","attributes":{"SessionStage":"Race1","TrackId":1534602052,"RaceLength":17},
             "members":[{"refid":7,"steamid":"76561198000000007","name":"Ann"}],
             "participants":[
              {"id":3,"attributes":{"RefId":9,"Name":"Bob","IsPlayer":1,"RacePosition":2,"CurrentLap":4,"CurrentSector":2,
                "Sector1Time":39900,"Sector2Time":0,"Sector3Time":31600,"LastLapTime":104100,"FastestLapTime":103900,"State":"Racing","VehicleId":55}},
              {"id":1,"attributes":{"RefId":7,"Name":"Ann","IsPlayer":1,"RacePosition":1,"CurrentLap":4,"CurrentSector":3,
                "Sector1Time":39800,"Sector2Time":32400,"Sector3Time":31000,"LastLapTime":103500,"FastestLapTime":103500,"State":"Racing","VehicleId":55}},
              {"id":2,"attributes":{"Name":"Zed (AI)","IsPlayer":0,"RacePosition":0,"State":"InGarage"}},
              {"id":4,"attributes":{"IsPlayer":0}}
             ]}""";

    @Test
    void ordersCarsByPositionAndLeavesOutUnsetTimes() throws Exception {
        var board = LiveTiming.summarize(RacemgrUtils.JSON.readTree(STATUS));

        assertThat(board.stage()).isEqualTo("Race1");
        assertThat(board.trackId()).isEqualTo("1534602052");
        assertThat(board.raceLength()).isEqualTo(17);
        // Nameless participants are dropped; a car without a position goes last
        assertThat(board.cars()).extracting(LiveTiming.Car::name).containsExactly("Ann", "Bob", "Zed (AI)");
        var bob = board.cars().get(1);
        assertThat(bob.s1()).isEqualTo(39900);
        assertThat(bob.s2()).isNull();
        assertThat(bob.bestLap()).isEqualTo(103900);
        assertThat(bob.player()).isTrue();
        assertThat(board.cars().get(2).player()).isFalse();
        assertThat(board.cars().get(2).position()).isNull();
    }

    @Test
    void isPublicSoItNeverCarriesIds() throws Exception {
        String json = RacemgrUtils.JSON.writeValueAsString(LiveTiming.summarize(RacemgrUtils.JSON.readTree(STATUS)));
        assertThat(json).doesNotContain("refid").doesNotContain("RefId").doesNotContain("steam").doesNotContain("76561198000000007");
    }

    @Test
    void toleratesAnIdleServer() throws Exception {
        var board = LiveTiming.summarize(RacemgrUtils.JSON.readTree("{\"state\":\"Idle\",\"name\":\"\",\"joinable\":false,\"max_member_count\":0}"));
        assertThat(board.cars()).isEmpty();
        assertThat(board.stage()).isEqualTo("Idle");
        assertThat(LiveTiming.summarize(RacemgrUtils.JSON.readTree("{}")).cars()).isEmpty();
    }

    @Test
    void theLogComesNewestFirstWithBoundedValues() throws Exception {
        var response = RacemgrUtils.JSON.readTree("""
                {"first":0,"count":3,"events":[
                 {"index":0,"time":100,"name":"SessionCreated","attributes":{}},
                 {"index":1,"time":105,"name":"PlayerJoined","refid":7,"attributes":{"Name":"Ann","Steam":76561198000000007}},
                 {"index":2,"time":110,"name":"ServerChat","attributes":{"Message":"%s"}},
                 {"time":111,"attributes":{}}]}""".formatted("x".repeat(500)));
        var events = GameServerLog.latestFirst(response);

        assertThat(events).extracting(GameServerLog.Event::name).containsExactly("ServerChat", "PlayerJoined", "SessionCreated");
        assertThat(events.get(1).refid()).isEqualTo(7);
        assertThat(events.get(1).attributes()).containsEntry("Name", "Ann").containsEntry("Steam", "76561198000000007");
        assertThat(events.get(0).attributes().get("Message")).hasSize(203).endsWith("...");
        assertThat(GameServerLog.latestFirst(RacemgrUtils.JSON.readTree("{}"))).isEmpty();
    }

    @Test
    void theLogSizeIsClamped() {
        assertThat(GameServerLog.clampCount(null)).isEqualTo(100);
        assertThat(GameServerLog.clampCount(0)).isEqualTo(1);
        assertThat(GameServerLog.clampCount(99999)).isEqualTo(500);
    }

    @Test
    void theWhitelistUsesTheSameFormatAndIsOnlyActiveWhenServerCfgReferencesIt(@TempDir Path dir) throws Exception {
        // The server's own sample: an empty bracketed list
        Path file = dir.resolve("whitelist.cfg");
        Files.writeString(file, "// sample\n// empty list\n\n[\n]\n");
        var list = new SteamIdList(file);
        assertThat(list.list()).isEmpty();

        list.add("76561198000000001", "Ann");
        list.add("76561198000000002", "");
        assertThat(Files.readString(file)).isEqualTo("// sample\n// empty list\n\n[\n76561198000000001 // Ann\n76561198000000002\n]\n");
        assertThat(list.remove("76561198000000001")).isTrue();
        assertThat(list.list()).extracting(SteamIdList.Entry::steamId).containsExactly("76561198000000002");

        // A file this class doesn't understand is never rewritten, and the message names that file
        Files.writeString(file, "{ \"Ann\": 76561198000000001 }");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> list.add("76561198000000003", ""))
                .isInstanceOf(SteamIdList.UnsupportedFormatException.class).hasMessageContaining("whitelist.cfg");
    }
}
