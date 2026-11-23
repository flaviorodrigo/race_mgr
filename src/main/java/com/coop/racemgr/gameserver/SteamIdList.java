package com.coop.racemgr.gameserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One of the game server's Steam ID lists: the permanent ban list (`blackList` in server.cfg) or the whitelist
 * (`whiteList`). The server's own samples are bracketed lists with one Steam ID per line and "//" comments:
 *
 * <pre>
 * // note
 * [
 * 76561198000000001 // optional note
 * ]
 * </pre>
 *
 * Commas between entries and quoted IDs are tolerated (the format allows them) and kept as they are. Anything else in
 * the file is never rewritten: a file with a shape this class doesn't understand is reported as unsupported.
 */
public class SteamIdList {
    public static final String FILE_NAME = "blacklist.cfg";
    public static final String WHITELIST_FILE_NAME = "whitelist.cfg";

    // 5+ digits so the server's own placeholder sample entry can be seen and removed; adding needs a real 17 digit id
    private static final Pattern ENTRY = Pattern.compile("^(\"?)(\\d{5,20})\"?\\s*(,?)\\s*(?://\\s*(.*))?$");
    private static final Pattern STEAM_ID = Pattern.compile("^\\d{17}$");
    private static final Pattern OPEN = Pattern.compile("^\\[\\s*(?://.*)?$");
    private static final Pattern CLOSE = Pattern.compile("^\\]\\s*(?://.*)?$");

    public record Entry(String steamId, String note) {}

    public static class UnsupportedFormatException extends Exception {
        public UnsupportedFormatException(String message) {
            super(message);
        }
    }

    private final Path file;

    public SteamIdList(Path file) {
        this.file = file;
    }

    public static SteamIdList forServer() {
        return new SteamIdList(GameServerPaths.root().resolve(FILE_NAME));
    }

    public static SteamIdList whitelistForServer() {
        return new SteamIdList(GameServerPaths.root().resolve(WHITELIST_FILE_NAME));
    }

    /**
     * True when server.cfg points its (uncommented) whiteList at whitelist.cfg. While it does, the game server lets
     * only the listed players in, and an empty list lets everybody in.
     */
    public static boolean whitelistReferenced() {
        try {
            Path config = GameServerPaths.root().resolve("server.cfg");
            return Files.exists(config) && Files.readAllLines(config, StandardCharsets.UTF_8).stream()
                    .map(String::trim)
                    .filter(l -> !l.startsWith("//"))
                    .anyMatch(l -> l.contains("whiteList") && l.contains(WHITELIST_FILE_NAME));
        } catch (Exception e) {
            return false;
        }
    }

    /** Any id that can appear in the list (the server's sample has a short placeholder), so it can be removed too. */
    public static boolean isListedId(String value) {
        return value != null && value.matches("^\\d{5,20}$");
    }

    public static boolean isSteamId(String value) {
        return value != null && STEAM_ID.matcher(value).matches();
    }

    public List<Entry> list() throws IOException, UnsupportedFormatException {
        List<Entry> entries = new ArrayList<>();
        for (String line : lines()) {
            Matcher m = ENTRY.matcher(line.trim());
            if (m.matches()) {
                entries.add(new Entry(m.group(2), m.group(4) == null ? "" : m.group(4).trim()));
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
        boolean quoted = lines.stream().map(l -> ENTRY.matcher(l.trim())).filter(Matcher::matches).anyMatch(m -> !m.group(1).isEmpty());
        String entry = (quoted ? "\"" + steamId + "\"" : steamId) + (cleanNote.isEmpty() ? "" : " // " + cleanNote);

        int close = lastIndexOf(lines, CLOSE);
        if (lines.stream().noneMatch(l -> OPEN.matcher(l.trim()).matches())) {
            // A new or empty file: start the list
            lines.removeIf(l -> l.trim().isEmpty());
            lines.add("[");
            lines.add(entry);
            lines.add("]");
        } else if (close < 0) {
            throw new UnsupportedFormatException("The list has no closing bracket. Edit " + fileName() + " by hand.");
        } else {
            lines.add(close, entry);
        }
        write(normalizeCommas(lines));
    }

    public synchronized boolean remove(String steamId) throws IOException, UnsupportedFormatException {
        List<String> kept = new ArrayList<>();
        boolean removed = false;
        for (String line : lines()) {
            Matcher m = ENTRY.matcher(line.trim());
            if (m.matches() && m.group(2).equals(steamId)) {
                removed = true;
            } else {
                kept.add(line);
            }
        }
        if (removed) {
            write(normalizeCommas(kept));
        }
        return removed;
    }

    private String fileName() {
        return file.getFileName().toString();
    }

    private static int lastIndexOf(List<String> lines, Pattern p) {
        for (int i = lines.size() - 1; i >= 0; i--) {
            if (p.matcher(lines.get(i).trim()).matches()) {
                return i;
            }
        }
        return -1;
    }

    // If the file separates entries with commas, keep that true after an add or remove: every entry but the last has one
    private static List<String> normalizeCommas(List<String> lines) {
        List<Integer> entryLines = new ArrayList<>();
        boolean commaStyle = false;
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = ENTRY.matcher(lines.get(i).trim());
            if (m.matches()) {
                entryLines.add(i);
                commaStyle |= !m.group(3).isEmpty();
            }
        }
        if (!commaStyle) {
            return lines;
        }
        List<String> result = new ArrayList<>(lines);
        for (int n = 0; n < entryLines.size(); n++) {
            int i = entryLines.get(n);
            Matcher m = ENTRY.matcher(lines.get(i).trim());
            m.matches();
            String id = m.group(1) + m.group(2) + m.group(1);
            String comma = n < entryLines.size() - 1 ? "," : "";
            String note = m.group(4) == null ? "" : " // " + m.group(4).trim();
            result.set(i, id + comma + note);
        }
        return result;
    }

    // All lines, after checking every one is something this class understands
    private List<String> lines() throws IOException, UnsupportedFormatException {
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        for (String line : lines) {
            String t = line.trim();
            boolean understood = t.isEmpty() || t.startsWith("//") || OPEN.matcher(t).matches() || CLOSE.matcher(t).matches()
                    || ENTRY.matcher(t).matches();
            if (!understood) {
                throw new UnsupportedFormatException("The list file has a layout Race Manager doesn't edit (one Steam ID per line between [ and ]). Edit "
                        + fileName() + " by hand.");
            }
        }
        boolean hasEntries = lines.stream().anyMatch(l -> ENTRY.matcher(l.trim()).matches());
        boolean hasBrackets = lines.stream().anyMatch(l -> OPEN.matcher(l.trim()).matches());
        if (hasEntries && !hasBrackets) {
            throw new UnsupportedFormatException("The list file has no [ ] list. Edit " + fileName() + " by hand.");
        }
        return lines;
    }

    // One line separator after every line, as Files.write(lines) does
    private void write(List<String> lines) throws IOException {
        StringBuilder text = new StringBuilder();
        lines.forEach(line -> text.append(line).append(System.lineSeparator()));
        GameServerFiles.replace(file, text.toString());
    }
}
