package com.coop.racemgr.gameserver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RotationAddonTest {
    private static Map<String, Object> rotation(String... tracks) {
        return Map.of("default", Map.of("RaceLength", 15), "rotation", java.util.Arrays.stream(tracks).map(t -> Map.of("TrackId", t)).toList());
    }

    @Test
    void installsTheAddonAndKeepsItsOwnConfigOnTheNextInstall(@TempDir Path dir) throws Exception {
        RotationAddon addon = new RotationAddon(dir);
        assertThat(addon.isInstalled()).isFalse();

        addon.install();
        assertThat(addon.isInstalled()).isTrue();
        Path folder = dir.resolve("lua/racemgr_rotate");
        assertThat(folder.resolve("racemgr_rotate.lua")).exists();
        assertThat(folder.resolve("racemgr_rotate.json")).exists();
        assertThat(Files.readString(folder.resolve("racemgr_rotate.lua"))).contains("ROTATION_FILE");

        Files.writeString(folder.resolve("racemgr_rotate_default_config.json"), "mine");
        Files.writeString(folder.resolve("racemgr_rotate.lua"), "old");
        addon.install();
        assertThat(Files.readString(folder.resolve("racemgr_rotate_default_config.json"))).isEqualTo("mine");
        assertThat(Files.readString(folder.resolve("racemgr_rotate.lua"))).contains("ROTATION_FILE");
    }

    @Test
    void handsOverARotationWithAGrowingVersion(@TempDir Path dir) throws Exception {
        RotationAddon addon = new RotationAddon(dir);
        long first = addon.writeRotation(rotation("A", "B"));
        long second = addon.writeRotation(rotation("A", "B", "C"));
        assertThat(second).isGreaterThan(first);
        String text = Files.readString(dir.resolve("lua_config/racemgr_rotation.lua"));
        assertThat(text).contains("version = " + second).contains("[\"TrackId\"] = \"C\"");
    }

    @Test
    void readsWhatTheAddonReports(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("lua_config"));
        Files.writeString(dir.resolve("lua_config/racemgr_rotation_status.lua"),
                "return { version = 1795567161001, index = 3, rounds = 9, ok = true, note = \"applied\" }\n");
        var reported = (Map<?, ?>) new RotationAddon(dir).status().get("reported");
        assertThat(reported.get("version")).isEqualTo(1795567161001L);
        assertThat(reported.get("index")).isEqualTo(3L);
        assertThat(reported.get("rounds")).isEqualTo(9L);
        assertThat(reported.get("ok")).isEqualTo(true);
        assertThat(reported.get("note")).isEqualTo("applied");
    }

    @Test
    void knowsWhichRotationAddonServerCfgLoads(@TempDir Path dir) throws Exception {
        RotationAddon addon = new RotationAddon(dir);
        addon.install();
        String cfg = """
                luaApiAddons : [
                    "sms_base",
                    // "sms_rotate",
                    "racemgr_rotate",
                    "sms_stats",
                ]
                """;
        Files.writeString(dir.resolve("server.cfg"), cfg);
        var status = addon.status();
        assertThat(status.get("listed")).isEqualTo(true);
        assertThat(status.get("smsRotateListed")).isEqualTo(false);
        assertThat(status.get("ready")).isEqualTo(true);

        Files.writeString(dir.resolve("server.cfg"), cfg.replace("// \"sms_rotate\"", "\"sms_rotate\""));
        var both = addon.status();
        assertThat(both.get("conflict")).isEqualTo(true);
        assertThat(both.get("ready")).isEqualTo(false);

        Files.writeString(dir.resolve("server.cfg"), "luaApiAddons : [ \"sms_base\", \"sms_rotate\" ]\n");
        var before = addon.status();
        assertThat(before.get("listed")).isEqualTo(false);
        assertThat(before.get("ready")).isEqualTo(false);
    }

    @Test
    void anAddonNotInstalledReportsNothing(@TempDir Path dir) {
        var status = new RotationAddon(dir).status();
        assertThat(status.get("installed")).isEqualTo(false);
        assertThat(status.get("reported")).isNull();
        assertThat(List.of(status.get("ready"))).containsExactly(false);
    }
}
