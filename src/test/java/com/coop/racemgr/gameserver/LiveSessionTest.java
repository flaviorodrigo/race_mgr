package com.coop.racemgr.gameserver;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class LiveSessionTest {
    private static final String STATUS = """
            {"attributes": {"SessionStage": "Race", "TrackId": 1234, "MaxPlayers": 12},
             "members": [
               {"refid": 7, "steamid": "76561198000000001", "name": "Ayrton S", "attributes": {"Ping": 42, "VehicleId": 1}},
               {"refid": 9, "steamid": "76561198000000002", "name": "Alain P", "ping": 77},
               {"refid": 11, "steamid": "76561198000000003"}
             ],
             "participants": []}
            """;

    private final tools.jackson.databind.JsonNode status = JsonMapper.shared().readTree(STATUS);

    @Test
    void adminViewIncludesIdsAndPing() {
        var summary = LiveSession.summarize(status, true);

        assertThat(summary.stage()).isEqualTo("Race");
        assertThat(summary.trackId()).isEqualTo("1234");
        assertThat(summary.maxPlayers()).isEqualTo(12);
        // A member without a name is skipped
        assertThat(summary.players()).isEqualTo(2);
        assertThat(summary.members()).extracting(LiveSession.Member::name).containsExactly("Ayrton S", "Alain P");
        assertThat(summary.members().get(0).refid()).isEqualTo(7);
        assertThat(summary.members().get(0).steamid()).isEqualTo("76561198000000001");
        assertThat(summary.members()).extracting(LiveSession.Member::ping).containsExactly(42, 77);
    }

    @Test
    void publicViewNeverCarriesReferenceOrSteamIds() throws Exception {
        var summary = LiveSession.summarize(status, false);

        assertThat(summary.members()).allSatisfy(m -> {
            assertThat(m.refid()).isNull();
            assertThat(m.steamid()).isNull();
        });
        String json = JsonMapper.shared().writeValueAsString(summary);
        assertThat(json).doesNotContain("steamid").doesNotContain("refid").doesNotContain("7656119");
        assertThat(json).contains("Ayrton S");
    }

    @Test
    void toleratesAnEmptyOrOddResponse() {
        var summary = LiveSession.summarize(JsonMapper.shared().readTree("{}"), true);
        assertThat(summary.players()).isZero();
        assertThat(summary.stage()).isNull();
        assertThat(summary.maxPlayers()).isNull();

        var odd = LiveSession.summarize(JsonMapper.shared().readTree("{\"members\": \"nope\", \"attributes\": {\"MaxPlayers\": \"abc\"}}"), true);
        assertThat(odd.players()).isZero();
        assertThat(odd.maxPlayers()).isNull();
    }

    @Test
    void onlyPlausibleReferenceIdsAreAccepted() {
        assertThat(LiveSession.isValidRefId(0)).isTrue();
        assertThat(LiveSession.isValidRefId(65535)).isTrue();
        assertThat(LiveSession.isValidRefId(null)).isFalse();
        assertThat(LiveSession.isValidRefId(-1)).isFalse();
        assertThat(LiveSession.isValidRefId(70000)).isFalse();
    }

    @Test
    void chatMessagesAreCleanedAndBounded() {
        assertThat(LiveSession.cleanChatMessage("  Race starts in 5\n minutes ")).isEqualTo("Race starts in 5  minutes");
        assertThat(LiveSession.cleanChatMessage("   ")).isNull();
        assertThat(LiveSession.cleanChatMessage(null)).isNull();
        assertThat(LiveSession.cleanChatMessage("x".repeat(201))).isNull();
        assertThat(LiveSession.cleanChatMessage("x".repeat(200))).hasSize(200);
    }

    @Test
    void queryValuesCannotInjectExtraParameters() {
        String base = "http://127.0.0.1:9000/api";
        assertThat(LiveSession.kickUri(base, 7).toString()).isEqualTo(base + "/session/kick?refid=7");

        var uri = LiveSession.chatUri(base, "hi&refid=1 #now=yes+more?");
        assertThat(uri.getRawQuery()).startsWith("message=");
        // The only unencoded & or = in the query is the one after the parameter name
        assertThat(uri.getRawQuery().substring("message=".length())).doesNotContain("&").doesNotContain("=").doesNotContain("#");
        assertThat(uri.getQuery()).isEqualTo("message=hi&refid=1 #now=yes+more?");
    }
}
