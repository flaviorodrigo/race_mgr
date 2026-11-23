package com.coop.racemgr.gameserver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and changes a few well-known settings in the game server's server.cfg without rewriting the file: only the
 * value after the key is replaced, so comments, ordering and everything else stay byte for byte as they were.
 * The file is JSON-like with comments, so this scans for `key : value` instead of parsing it.
 */
public class ServerConfigFile {
    public static final String FILE_NAME = "server.cfg";
    public static final int MAX_TEXT = 64;

    public enum Kind { TEXT, INT, BOOL }

    /** A setting that may be edited. `section` is null for top-level keys or the name of an object block. */
    public record Setting(String key, String section, Kind kind, int min, int max) {
        public String id() {
            return section == null ? key : section + "." + key;
        }
    }

    public static final Map<String, Setting> SETTINGS = new LinkedHashMap<>();

    static {
        add(new Setting("name", null, Kind.TEXT, 0, 0));
        add(new Setting("password", null, Kind.TEXT, 0, 0));
        add(new Setting("maxPlayerCount", null, Kind.INT, 1, 64));
        add(new Setting("secure", null, Kind.BOOL, 0, 0));
        add(new Setting("allowEmptyJoin", null, Kind.BOOL, 0, 0));
        add(new Setting("GridSize", "sessionAttributes", Kind.INT, 1, 64));
        add(new Setting("MaxPlayers", "sessionAttributes", Kind.INT, 1, 64));
        add(new Setting("PracticeLength", "sessionAttributes", Kind.INT, 0, 600));
        add(new Setting("QualifyLength", "sessionAttributes", Kind.INT, 0, 600));
        add(new Setting("RaceLength", "sessionAttributes", Kind.INT, 1, 999));
        add(new Setting("OpponentDifficulty", "sessionAttributes", Kind.INT, 0, 100));
    }

    private static void add(Setting s) {
        SETTINGS.put(s.id(), s);
    }

    private static final String VALUE = "(\"(?:[^\"\\\\\\n]|\\\\.)*\"|-?\\d+|true|false)";

    private final Path file;

    public ServerConfigFile(Path file) {
        this.file = file;
    }

    public static ServerConfigFile forServer() {
        return new ServerConfigFile(GameServerPaths.root().resolve(FILE_NAME));
    }

    /** Current values (strings as text, numbers, booleans). The password is reported only as set / not set. */
    public Map<String, Object> read() throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Map<String, Object> values = new LinkedHashMap<>();
        for (Setting s : SETTINGS.values()) {
            Matcher m = locate(text, s);
            if (m == null) {
                continue;
            }
            Object value = decode(m.group(1));
            if (s.id().equals("password")) {
                values.put("passwordSet", !String.valueOf(value).isEmpty());
            } else {
                values.put(s.id(), value);
            }
        }
        return values;
    }

    /** A top-level text setting that isn't one of the editable ones (e.g. the names of other files), or null. */
    public static String readString(Path file, String key) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        Matcher m = locate(Files.readString(file, StandardCharsets.UTF_8), new Setting(key, null, Kind.TEXT, 0, 0));
        return m != null && m.group(1).startsWith("\"") ? String.valueOf(decode(m.group(1))) : null;
    }

    /** @return a problem description, or null when every value is acceptable for its setting */
    public static String validate(Map<String, Object> changes) {
        if (changes == null || changes.isEmpty()) {
            return "No settings given";
        }
        for (var entry : changes.entrySet()) {
            Setting s = SETTINGS.get(entry.getKey());
            if (s == null) {
                return "Setting not allowed: " + entry.getKey().replaceAll("[^A-Za-z0-9_.]", "?");
            }
            Object v = entry.getValue();
            switch (s.kind()) {
                case TEXT -> {
                    if (!(v instanceof String str) || str.length() > MAX_TEXT || str.matches(".*\\p{Cntrl}.*")) {
                        return s.key() + " must be text of up to " + MAX_TEXT + " characters";
                    }
                }
                case INT -> {
                    if (!(v instanceof Integer n) || n < s.min() || n > s.max()) {
                        return s.key() + " must be a whole number from " + s.min() + " to " + s.max();
                    }
                }
                case BOOL -> {
                    if (!(v instanceof Boolean)) {
                        return s.key() + " must be true or false";
                    }
                }
            }
        }
        return null;
    }

    /** Writes the changes, keeping a one-step backup (server.cfg.bak) of the file as it was. */
    public synchronized void write(Map<String, Object> changes) throws IOException {
        String problem = validate(changes);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }

        String text = Files.readString(file, StandardCharsets.UTF_8);
        for (var entry : changes.entrySet()) {
            Setting s = SETTINGS.get(entry.getKey());
            Matcher m = locate(text, s);
            if (m == null) {
                throw new IOException(s.key() + " was not found in " + FILE_NAME + "; add it by hand first");
            }
            text = text.substring(0, m.start(1)) + encode(entry.getValue()) + text.substring(m.end(1));
        }

        Files.copy(file, file.resolveSibling(FILE_NAME + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        GameServerFiles.replace(file, text);
    }

    // --- finding a key's value ---------------------------------------------------------------------------

    private static Matcher locate(String text, Setting s) {
        int[] range = rangeOf(text, s.section());
        if (range == null) {
            return null;
        }
        // Only a key at the start of a line (after indentation), never one inside a comment or string
        Pattern p = Pattern.compile("(?m)^[ \\t]*\"?" + Pattern.quote(s.key()) + "\"?[ \\t]*:[ \\t]*" + VALUE);
        Matcher m = p.matcher(text);
        m.region(range[0], range[1]);
        while (m.find()) {
            if (depthAt(text, m.start()) == (s.section() == null ? 0 : 1)) {
                return m;
            }
        }
        return null;
    }

    // The part of the file to search: all of it, or the inside of a named top-level object
    private static int[] rangeOf(String text, String section) {
        if (section == null) {
            return new int[]{0, text.length()};
        }
        Matcher m = Pattern.compile("(?m)^[ \\t]*\"?" + Pattern.quote(section) + "\"?[ \\t]*:[ \\t]*\\{").matcher(text);
        while (m.find()) {
            if (depthAt(text, m.start()) != 0) {
                continue;
            }
            int open = m.end() - 1;
            int depth = 0;
            for (int i = open; i < text.length(); i++) {
                i = skipNonCode(text, i);
                if (i >= text.length()) {
                    break;
                }
                char c = text.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    return new int[]{open + 1, i};
                }
            }
        }
        return null;
    }

    // Brace depth at a position, ignoring braces inside strings and comments
    private static int depthAt(String text, int position) {
        int depth = 0;
        for (int i = 0; i < position; i++) {
            i = skipNonCode(text, i);
            if (i >= position) {
                break;
            }
            char c = text.charAt(i);
            if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                depth--;
            }
        }
        return depth;
    }

    // If a string or comment starts at i, returns the index just before its end so the caller's i++ moves past it
    private static int skipNonCode(String text, int i) {
        char c = text.charAt(i);
        if (c == '"') {
            int j = i + 1;
            while (j < text.length() && text.charAt(j) != '"' && text.charAt(j) != '\n') {
                j += text.charAt(j) == '\\' ? 2 : 1;
            }
            return Math.min(j, text.length() - 1) + (j >= text.length() ? 1 : 0);
        }
        boolean slashComment = c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '/';
        if (slashComment || c == '#') {
            int end = text.indexOf('\n', i);
            return end < 0 ? text.length() : end;
        }
        return i;
    }

    private static Object decode(String literal) {
        if (literal.startsWith("\"")) {
            return literal.substring(1, literal.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        if ("true".equals(literal) || "false".equals(literal)) {
            return Boolean.parseBoolean(literal);
        }
        return Integer.parseInt(literal);
    }

    private static String encode(Object value) {
        if (value instanceof String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return String.valueOf(value);
    }
}
