package com.coop.racemgr.gameserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Replaces a game server file's contents. Normally through a temporary file and a rename, so a crash never leaves a
 * half-written file. A file that is itself mounted into the container (a single-file Docker bind mount, e.g. a
 * server.cfg kept outside the server folder) can't be renamed over ("Device or resource busy"): that file is then
 * written in place instead.
 */
public final class GameServerFiles {
    private GameServerFiles() {}

    /** How the temporary file is moved over the target; replaceable in tests. */
    interface Mover {
        void move(Path from, Path to) throws IOException;
    }

    static Mover mover = (from, to) -> Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);

    public static void replace(Path file, String text) throws IOException {
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), file.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                mover.move(temp, file);
            } catch (FileSystemException e) {
                if (!Files.isRegularFile(file)) {
                    throw e;
                }
                Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
