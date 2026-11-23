package com.coop.racemgr.gameserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The game server's permanent ban list (the file named by `blackList` in server.cfg), one Steam ID per line with an
 * optional "// note". Lines this class doesn't understand are never rewritten: if the file holds anything else the
 * list is shown as unsupported instead of risking damage to it.
 */
public class BlackList {
    public static final String FILE_NAME = "blacklist.cfg";

    private static final Pattern ENTRY = Pattern.compile("^(\\d{17})\\s*(?:(?://|#)\\s*(.*))?$");
    private static final Pattern STEAM_ID = Pattern.compile("^\\d{17}$");

    public record Entry(String steamId, String note) {}

    public static class UnsupportedFormatException extends Exception {
        public UnsupportedFormatException(String message) {
            super(message);
        }
    }

    private final Path file;

    public BlackList(Path file) {
        this.file = file;
    }

    public static BlackList forServer() {
        return new BlackList(GameServerPaths.root().resolve(FILE_NAME));
    }

    public static boolean isSteamId(String value) {
        return value != null && STEAM_ID.matcher(value).matches();
    }

    public List<Entry> list() throws IOException, UnsupportedFormatException {
        List<Entry> entries = new ArrayList<>();
        for (String line : lines()) {
            Entry entry = parse(line);
            if (entry != null) {
                entries.add(entry);
            }
        }
        return entries;
    }

    public synchronized void add(String steamId, String note) throws IOException, UnsupportedFormatException {
        if (!isSteamId(steamId)) {
            throw new IllegalArgumentException("A Steam ID is 17 digits");
        }
        if (list().stream().anyMatch(e -> e.steamId().equals(steamId))) {
            return;
        }
        List<String> lines = lines();
        String cleanNote = note == null ? "" : note.replaceAll("\\p{Cntrl}", " ").trim();
        lines.add(steamId + (cleanNote.isEmpty() ? "" : " // " + cleanNote));
        write(lines);
    }

    public synchronized boolean remove(String steamId) throws IOException, UnsupportedFormatException {
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String line : lines()) {
            Entry entry = parse(line);
            if (entry != null && entry.steamId().equals(steamId)) {
                removed = true;
            } else {
                kept.add(line);
            }
        }
        if (removed) {
            write(kept);
        }
        return removed;
    }

    private static Entry parse(String line) {
        String trimmed = line.trim();
        Matcher m = ENTRY.matcher(trimmed);
        return m.matches() ? new Entry(m.group(1), m.group(2) == null ? "" : m.group(2).trim()) : null;
    }

    private List<String> lines() throws IOException, UnsupportedFormatException {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        for (String line : lines) {
            String trimmed = line.trim();
            boolean blankOrComment = trimmed.isEmpty() || trimmed.startsWith("//") || trimmed.startsWith("#");
            if (!blankOrComment && parse(line) == null) {
                throw new UnsupportedFormatException("The ban list file has a format Race Manager doesn't edit. Edit " + FILE_NAME + " by hand.");
            }
        }
        return lines;
    }

    private void write(List<String> lines) throws IOException {
        Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), FILE_NAME, ".tmp");
        try {
            Files.write(temp, lines, StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
