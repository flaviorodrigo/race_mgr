package com.coop.racemgr.gameserver;

import com.coop.racemgr.gameserver.PerformanceFiles.Kind;
import com.coop.racemgr.gameserver.PerformanceFiles.Table;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PerformanceFilesTest {
    // The three sample files that ship with the dedicated server
    private static void copySamples(Path dir) throws IOException {
        for (String f : new String[]{"driver_ballast.json", "driver_drag_scalar.json", "driver_power_scalar.json"}) {
            try (var in = PerformanceFilesTest.class.getResourceAsStream("/ams2_samples/" + f)) {
                Files.write(dir.resolve(f), in.readAllBytes());
            }
        }
    }

    @Test
    void readsTheServersSampleFiles(@TempDir Path dir) throws Exception {
        copySamples(dir);
        Map<Kind, Table> tables = new PerformanceFiles(dir).read();

        assertThat(tables.get(Kind.BALLAST).enabled()).isTrue();
        assertThat(tables.get(Kind.BALLAST).defaultValue()).isZero();
        assertThat(tables.get(Kind.BALLAST).drivers()).containsEntry("76561198000000001", 20.0).containsEntry("76561198000000002", 35.0);
        assertThat(tables.get(Kind.POWER).defaultValue()).isEqualTo(1.0);
        assertThat(tables.get(Kind.POWER).drivers()).containsEntry("76561198000000001", 1.025);
        assertThat(tables.get(Kind.DRAG).drivers()).containsEntry("76561198000000002", 0.985);
    }

    @Test
    void savesChangesKeepingUnknownKeysAndABackup(@TempDir Path dir) throws Exception {
        copySamples(dir);
        Files.writeString(dir.resolve("driver_ballast.json"),
                "{\"enabled\": true, \"defaultBallastKg\": 0, \"note\": \"keep me\", \"drivers\": {\"76561198000000001\": 20}}");
        var files = new PerformanceFiles(dir);
        Map<Kind, Table> tables = files.read();

        Map<String, Double> drivers = new LinkedHashMap<>(tables.get(Kind.BALLAST).drivers());
        drivers.put("76561198000000009", 12.3456);
        tables.put(Kind.BALLAST, new Table(true, 5, drivers, "driver_ballast.json"));
        files.write(tables);

        String saved = Files.readString(dir.resolve("driver_ballast.json"));
        assertThat(saved).contains("\"note\" : \"keep me\"").contains("\"defaultBallastKg\" : 5.0").contains("\"76561198000000009\" : 12.346");
        assertThat(files.read().get(Kind.BALLAST).drivers()).containsEntry("76561198000000009", 12.346);
        assertThat(Files.readString(dir.resolve("driver_ballast.json.bak"))).contains("keep me").doesNotContain("0000009");
    }

    @Test
    void aMissingFileReadsAsDisabledAndIsCreatedOnSave(@TempDir Path dir) throws Exception {
        var files = new PerformanceFiles(dir);
        Map<Kind, Table> tables = files.read();
        assertThat(tables.get(Kind.POWER).enabled()).isFalse();
        assertThat(tables.get(Kind.POWER).defaultValue()).isEqualTo(1.0);

        tables.put(Kind.POWER, new Table(true, 1.0, Map.of("76561198000000001", 0.97), "driver_power_scalar.json"));
        files.write(Map.of(Kind.POWER, tables.get(Kind.POWER)));
        assertThat(Files.readString(dir.resolve("driver_power_scalar.json"))).contains("0.97").contains("\"enabled\" : true");
    }

    @Test
    void rejectsOutOfRangeValuesAndBadIds(@TempDir Path dir) throws Exception {
        copySamples(dir);
        var files = new PerformanceFiles(dir);
        String original = Files.readString(dir.resolve("driver_ballast.json"));

        assertThat(PerformanceFiles.validate(Map.of(Kind.BALLAST, new Table(true, 0, Map.of("76561198000000001", 501.0), "x")))).contains("between");
        assertThat(PerformanceFiles.validate(Map.of(Kind.POWER, new Table(true, 2.0, Map.of(), "x")))).contains("default");
        assertThat(PerformanceFiles.validate(Map.of(Kind.DRAG, new Table(true, 1, Map.of("123", 1.0), "x")))).contains("17 digits");
        assertThat(PerformanceFiles.validate(Map.of(Kind.DRAG, new Table(true, 1, Map.of("76561198000000001", Double.NaN), "x")))).contains("between");
        assertThat(PerformanceFiles.validate(Map.of(Kind.BALLAST, new Table(true, 0, Map.of("76561198000000001", 10.0), "x")))).isNull();

        // One bad table means nothing at all is written
        Map<Kind, Table> mixed = new LinkedHashMap<>();
        mixed.put(Kind.BALLAST, new Table(true, 7, Map.of(), "x"));
        mixed.put(Kind.POWER, new Table(true, 9, Map.of(), "x"));
        assertThatThrownBy(() -> files.write(mixed)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(dir.resolve("driver_ballast.json"))).isEqualTo(original);
    }

    @Test
    void refusesToRewriteAFileThatIsNotPlainJson(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("driver_ballast.json"), "// my notes\n{\"enabled\": true, \"drivers\": {}}\n");
        var files = new PerformanceFiles(dir);
        assertThatThrownBy(files::read).isInstanceOf(IOException.class).hasMessageContaining("plain JSON");
    }

    @Test
    void fileNamesFromServerCfgCannotEscapeTheFolder(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("server.cfg"), "ballastFile : \"../../etc/passwd\"\npowerScalarFile : \"my_power.json\"\ndragScalarFile : \"drag.txt\"\n");
        Map<Kind, Table> tables = new PerformanceFiles(dir).read();

        assertThat(tables.get(Kind.BALLAST).file()).isEqualTo("driver_ballast.json");
        assertThat(tables.get(Kind.POWER).file()).isEqualTo("my_power.json");
        assertThat(tables.get(Kind.DRAG).file()).isEqualTo("driver_drag_scalar.json");
    }
}
