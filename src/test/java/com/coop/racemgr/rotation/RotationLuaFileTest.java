package com.coop.racemgr.rotation;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RotationLuaFileTest {
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    @Test
    void writesTheDefaultAndTheRoundsAsALuaTable() {
        Map<String, Object> config = map("persist_index", true,
                "defaultConfig", map("RaceLength", 15, "ManualPitStops", "Manual", "Flags", "ABS_ALLOWED,SC_ALLOWED"),
                "rotation", List.of(map("TrackId", "Laguna_Seca_2020"), map("TrackId", "Brasilia_Full", "RaceLength", 20)));

        assertThat(RotationLuaFile.render(config, 1795567161000L)).isEqualTo("""
                return {
                  version = 1795567161000,
                  default = { ["RaceLength"] = 15, ["ManualPitStops"] = "Manual", ["Flags"] = "ABS_ALLOWED,SC_ALLOWED" },
                  rotation = { { ["TrackId"] = "Laguna_Seca_2020" }, { ["TrackId"] = "Brasilia_Full", ["RaceLength"] = 20 } }
                }
                """);
    }

    @Test
    void acceptsDefaultUnderTheNameTheGameServerUses() {
        String text = RotationLuaFile.render(map("default", map("RaceLength", 5), "rotation", List.of()), 1);
        assertThat(text).contains("default = { [\"RaceLength\"] = 5 }").contains("rotation = {}");
    }

    @Test
    void wholeDoublesStayIntegersAndNegativeIdsSurvive() {
        String text = RotationLuaFile.render(map("default", map("RaceLength", 15.0, "TrackId", -559709709L, "OpponentDifficulty", 87.5),
                "rotation", List.of()), 1);
        assertThat(text).contains("[\"RaceLength\"] = 15,").contains("[\"TrackId\"] = -559709709,").contains("[\"OpponentDifficulty\"] = 87.5");
    }

    @Test
    void textCanNotBreakOutOfItsStringOrCarryControlCharacters() {
        String hostile = "x\"]] } os.execute('touch /tmp/pwned') --\\\né";
        String text = RotationLuaFile.render(map("default", map("Note", hostile), "rotation", List.of()), 1);
        // The quote and the backslash are escaped, the newline and the accented letter become decimal escapes
        assertThat(text).contains("x\\\"]] } os.execute('touch /tmp/pwned') --\\\\\\010\\195\\169\"");
        assertThat(text).doesNotContain("\n\n").doesNotContain("é");
    }

    @Test
    void booleansAreWrittenAndNullEntriesLeftOut() {
        Map<String, Object> defaults = map("A", true, "B", null);
        assertThat(RotationLuaFile.render(map("default", defaults, "rotation", List.of()), 1)).contains("default = { [\"A\"] = true }");
    }

    @Test
    void refusesWhatIsNotARotation() {
        assertThatThrownBy(() -> RotationLuaFile.render("nope", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RotationLuaFile.render(map("rotation", List.of()), 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RotationLuaFile.render(map("default", map("X", Double.NaN), "rotation", List.of()), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
