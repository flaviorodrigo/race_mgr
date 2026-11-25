package com.coop.racemgr.gameserver;

import com.coop.racemgr.rotation.RotationLuaFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The racemgr_rotate Lua addon on the game server: installing its files, handing it a rotation that it picks up while
 * the server runs, and reading what it reports back. Everything goes through files in the game server's folder.
 */
public class RotationAddon {
    public static final String NAME = "racemgr_rotate";
    private static final String[] FILES = {"racemgr_rotate.lua", "racemgr_rotate.json", "racemgr_rotate_default_config.json"};

    private final Path root;

    public RotationAddon(Path root) {
        this.root = root;
    }

    public static RotationAddon forServer() {
        return new RotationAddon(GameServerPaths.root());
    }

    private Path addonDir() {
        return root.resolve("lua").resolve(NAME);
    }

    private Path rotationFile() {
        return root.resolve("lua_config").resolve("racemgr_rotation.lua");
    }

    private Path statusFile() {
        return root.resolve("lua_config").resolve("racemgr_rotation_status.lua");
    }

    public boolean isInstalled() {
        return Files.isRegularFile(addonDir().resolve("racemgr_rotate.lua"));
    }

    /** Copies the addon into lua/racemgr_rotate. Its own config file is only created, never replaced. */
    public void install() throws IOException {
        Files.createDirectories(addonDir());
        Files.createDirectories(rotationFile().getParent());
        for (String name : FILES) {
            Path target = addonDir().resolve(name);
            if (name.endsWith("_default_config.json") && Files.exists(target)) {
                continue;
            }
            try (InputStream in = RotationAddon.class.getResourceAsStream("/lua/" + NAME + "/" + name)) {
                if (in == null) {
                    throw new IOException("The addon file " + name + " is missing from Race Manager");
                }
                GameServerFiles.replace(target, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    /** Hands a rotation to the addon. Returns the version it will report once it has loaded it. */
    public long writeRotation(Object config) throws IOException {
        long version = Math.max(System.currentTimeMillis(), lastVersion() + 1);
        String text = RotationLuaFile.render(config, version);
        Files.createDirectories(rotationFile().getParent());
        GameServerFiles.replace(rotationFile(), text);
        return version;
    }

    private long lastVersion() {
        try {
            if (Files.exists(rotationFile())) {
                Matcher m = Pattern.compile("version = (\\d+)").matcher(Files.readString(rotationFile(), StandardCharsets.UTF_8));
                return m.find() ? Long.parseLong(m.group(1)) : 0;
            }
        } catch (IOException | NumberFormatException e) {
            // A rotation file that can't be read is simply replaced
        }
        return 0;
    }

    /** What the addon wrote about itself (a Lua table: version, index, rounds, ok, note), or null. */
    private Map<String, Object> reported() {
        try {
            if (!Files.isRegularFile(statusFile())) {
                return null;
            }
            String text = Files.readString(statusFile(), StandardCharsets.UTF_8);
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : List.of("version", "index", "rounds")) {
                Matcher m = Pattern.compile(key + " = (-?\\d+)").matcher(text);
                if (m.find()) {
                    out.put(key, Long.parseLong(m.group(1)));
                }
            }
            Matcher ok = Pattern.compile("ok = (true|false)").matcher(text);
            out.put("ok", !ok.find() || Boolean.parseBoolean(ok.group(1)));
            Matcher note = Pattern.compile("note = \"((?:[^\"\\\\]|\\\\.)*)\"").matcher(text);
            out.put("note", note.find() ? note.group(1).replaceAll("\\\\(.)", "$1") : "");
            return out;
        } catch (IOException | NumberFormatException e) {
            return null;
        }
    }

    /** Whether server.cfg's luaApiAddons list names the addon / sms_rotate (commented-out lines don't count). */
    private boolean listed(String addon) {
        try {
            Path cfg = root.resolve("server.cfg");
            if (!Files.isRegularFile(cfg)) {
                return false;
            }
            String text = Files.readString(cfg, StandardCharsets.UTF_8);
            Matcher block = Pattern.compile("(?s)luaApiAddons\\s*:\\s*\\[(.*?)\\]").matcher(stripComments(text));
            return block.find() && Pattern.compile("\"" + Pattern.quote(addon) + "\"").matcher(block.group(1)).find();
        } catch (IOException e) {
            return false;
        }
    }

    private static String stripComments(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            int at = line.indexOf("//");
            out.append(at >= 0 && line.substring(0, at).chars().filter(c -> c == '"').count() % 2 == 0 ? line.substring(0, at) : line).append('\n');
        }
        return out.toString();
    }

    /**
     * The round the racemgr_rotate addon has queued for the next session, when that addon is the one in use (listed in
     * server.cfg instead of sms_rotate) and has reported. Otherwise null.
     */
    public Integer queuedRound() {
        if (!Boolean.TRUE.equals(status().get("ready"))) {
            return null;
        }
        Map<String, Object> reported = reported();
        return reported != null && reported.get("index") instanceof Long index && index > 0 ? index.intValue() : null;
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        boolean installed = isInstalled();
        boolean listed = listed(NAME);
        boolean smsListed = listed("sms_rotate");
        Map<String, Object> reported = reported();
        status.put("installed", installed);
        status.put("listed", listed);
        status.put("smsRotateListed", smsListed);
        // Both addons at once would each set the next session
        status.put("conflict", listed && smsListed);
        status.put("reported", reported);
        status.put("ready", installed && listed && !smsListed);
        return status;
    }
}
