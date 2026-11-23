package com.coop.racemgr.gameserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GameServerFilesTest {
    @AfterEach
    void normalMover() {
        GameServerFiles.mover = (from, to) -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
    }

    // What Linux answers when a rename targets a file that is a Docker bind mount of its own
    private static void mountedFile() {
        GameServerFiles.mover = (from, to) -> {
            throw new FileSystemException(from.toString(), to.toString(), "Device or resource busy");
        };
    }

    @Test
    void replacesThroughATemporaryFileAndLeavesNothingBehind(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("server.cfg");
        Files.writeString(file, "old");
        GameServerFiles.replace(file, "new");
        assertThat(Files.readString(file)).isEqualTo("new");
        try (var files = Files.list(dir)) {
            assertThat(files).containsExactly(file);
        }
    }

    @Test
    void aFileMountedOnItsOwnIsWrittenInPlace(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("server.cfg");
        Files.writeString(file, "a much longer old content than the new one");
        mountedFile();
        GameServerFiles.replace(file, "new");
        assertThat(Files.readString(file)).isEqualTo("new");
        try (var files = Files.list(dir)) {
            assertThat(files).containsExactly(file);
        }
    }

    @Test
    void aMissingFileIsNeverCreatedByTheFallback(@TempDir Path dir) {
        mountedFile();
        assertThatThrownBy(() -> GameServerFiles.replace(dir.resolve("nothing.cfg"), "x")).isInstanceOf(FileSystemException.class);
        assertThat(Files.exists(dir.resolve("nothing.cfg"))).isFalse();
    }

    @Test
    void theServerSettingsFormWorksOnAMountedServerCfg(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("server.cfg");
        Files.writeString(file, "// my notes\nname : \"Old name\"\nmaxPlayerCount : 16\n");
        mountedFile();
        new ServerConfigFile(file).write(Map.of("name", "Friday Night Racing"));
        assertThat(Files.readString(file)).isEqualTo("// my notes\nname : \"Friday Night Racing\"\nmaxPlayerCount : 16\n");
        assertThat(Files.readString(dir.resolve("server.cfg.bak"))).contains("Old name");
    }
}
