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

    // --- the whitelist: whether the game server uses whitelist.cfg ----------------------------------------------

    public static final String WHITELIST_LINE = "whiteList : { \"whitelist\" : \"" + SteamIdList.WHITELIST_FILE_NAME + "\" }";
    private static final Pattern WHITELIST_ACTIVE = Pattern.compile("(?m)^([ \\t]*)\"?whiteList\"?[ \\t]*:[^\\n]*");
    // Only the plain sample line is uncommented: the game server's own sample also lists a stranger's Steam ID
    private static final Pattern WHITELIST_COMMENTED = Pattern.compile("(?m)^([ \\t]*)//[ \\t]*(whiteList[ \\t]*:[ \\t]*\\{[ \\t]*\"whitelist\"[ \\t]*:[ \\t]*\""
            + Pattern.quote(SteamIdList.WHITELIST_FILE_NAME) + "\"[ \\t]*\\}[ \\t]*)$");

    /**
     * Makes the game server use whitelist.cfg (only the listed players can join) or stop using it. Turning it on
     * uncomments the plain sample line when there is one, else adds the line at the end; turning it off comments the line
     * out. Nothing else in the file changes, and the previous file is kept as server.cfg.bak.
     * @return whether the file changed
     */
    public synchronized boolean setWhitelistEnabled(boolean enabled) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Matcher active = findTopLevel(WHITELIST_ACTIVE, text);
        String changed;
        if (enabled) {
            if (active != null) {
                return false;
            }
            Matcher commented = findTopLevel(WHITELIST_COMMENTED, text);
            if (commented != null) {
                changed = text.substring(0, commented.start()) + commented.group(1) + commented.group(2) + text.substring(commented.end());
            } else {
                changed = text + (text.endsWith("\n") || text.isEmpty() ? "" : "\n") + WHITELIST_LINE + "\n";
            }
        } else {
            if (active == null) {
                return false;
            }
            String line = active.group();
            if (line.chars().filter(c -> c == '{').count() != line.chars().filter(c -> c == '}').count()) {
                throw new IOException("The whiteList entry in " + FILE_NAME + " spans several lines; comment it out by hand");
            }
            changed = text.substring(0, active.start()) + active.group(1) + "// " + line.substring(active.group(1).length()) + text.substring(active.end());
        }

        Files.copy(file, file.resolveSibling(FILE_NAME + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        GameServerFiles.replace(file, changed);
        return true;
    }

    // The first match that is a top-level entry of the file (not inside a block)
    private static Matcher findTopLevel(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            if (depthAt(text, m.start()) == 0) {
                return m;
            }
        }
        return null;
    }

    // --- the sessionAttributes block: the settings a session starts from ------------------------------------

    public static final String SESSION_BLOCK = "sessionAttributes";
    private static final Pattern ENTRY = Pattern.compile("(?m)^([ \\t]*)\"?([A-Za-z][A-Za-z0-9_]*)\"?[ \\t]*:[ \\t]*(-?\\d+)");

    /** Every whole-number setting in the sessionAttributes block, in file order (other values are left alone). */
    public Map<String, Integer> readSessionAttributes() throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        int[] range = rangeOf(text, SESSION_BLOCK);
        if (range == null) {
            throw new IOException("There is no " + SESSION_BLOCK + " block in " + FILE_NAME);
        }
        Map<String, Integer> values = new LinkedHashMap<>();
        Matcher m = ENTRY.matcher(text);
        m.region(range[0], range[1]);
        while (m.find()) {
            if (depthAt(text, m.start()) == 1) {
                try {
                    values.put(m.group(2), Integer.parseInt(m.group(3)));
                } catch (NumberFormatException e) {
                    // Not a 32-bit value: not one of ours, leave it
                }
            }
        }
        return values;
    }

    /**
     * Sets settings in the sessionAttributes block: a setting that is there has only its value replaced; one that isn't
     * is added at the end of the block. Comments, order and everything else stay as they were, and the previous file is
     * kept as server.cfg.bak. Nothing is ever removed.
     */
    public synchronized void writeSessionAttributes(Map<String, Integer> changes) throws IOException {
        String problem = SessionSettings.validate(changes);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }

        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (rangeOf(text, SESSION_BLOCK) == null) {
            throw new IOException("There is no " + SESSION_BLOCK + " block in " + FILE_NAME + "; add it by hand first");
        }

        Map<String, Integer> existing = readSessionAttributes();
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (var entry : changes.entrySet()) {
            if (existing.containsKey(entry.getKey())) {
                Matcher m = locate(text, new Setting(entry.getKey(), SESSION_BLOCK, Kind.INT, 0, 0));
                text = text.substring(0, m.start(1)) + entry.getValue() + text.substring(m.end(1));
            } else {
                missing.put(entry.getKey(), entry.getValue());
            }
        }
        if (!missing.isEmpty()) {
            text = addToSessionBlock(text, missing);
        }

        Files.copy(file, file.resolveSibling(FILE_NAME + ".bak"), StandardCopyOption.REPLACE_EXISTING);
        GameServerFiles.replace(file, text);
    }

    private static String addToSessionBlock(String text, Map<String, Integer> missing) {
        int[] range = rangeOf(text, SESSION_BLOCK);
        int close = range[1];
        int open = range[0] - 1;

        // The last piece of real content in the block, so the new entries can follow it with a comma between
        int lastCode = -1;
        for (int i = open + 1; i < close; i++) {
            int next = skipNonCode(text, i);
            if (next != i) {
                lastCode = Math.max(lastCode, text.charAt(i) == '"' ? next : lastCode);
                i = next;
                continue;
            }
            if (!Character.isWhitespace(text.charAt(i))) {
                lastCode = i;
            }
        }

        Matcher indent = ENTRY.matcher(text);
        indent.region(range[0], range[1]);
        String pad = indent.find() ? indent.group(1) : "    ";

        StringBuilder added = new StringBuilder();
        for (var entry : missing.entrySet()) {
            added.append(pad).append('"').append(entry.getKey()).append("\" : ").append(entry.getValue()).append(",\n");
        }

        // Insert on the line of the closing brace when it stands alone there, else just before it
        int lineStart = text.lastIndexOf('\n', close - 1) + 1;
        boolean alone = lineStart > open && text.substring(lineStart, close).isBlank();
        String insertion = alone ? added.toString() : "\n" + added;
        int at = alone ? lineStart : close;

        String result = text.substring(0, at) + insertion + text.substring(at);
        // A previous entry without a trailing comma needs one before the new ones
        if (lastCode > open && result.charAt(lastCode) != ',' && result.charAt(lastCode) != '{') {
            result = result.substring(0, lastCode + 1) + "," + result.substring(lastCode + 1);
        }
        return result;
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
