package com.coop.racemgr.gameserver;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Balance of performance: per-driver ballast, engine power and drag, kept by the game server in three small JSON
 * files (named by `ballastFile`, `powerScalarFile` and `dragScalarFile` in server.cfg). Each looks like:
 *
 * <pre>{ "enabled": true, "defaultBallastKg": 0, "drivers": { "76561198000000001": 20 } }</pre>
 *
 * Keys this class doesn't know about are kept when a file is saved.
 */
public class PerformanceFiles {
    public enum Kind {
        BALLAST("ballastFile", "driver_ballast.json", "defaultBallastKg", 0, 500),
        POWER("powerScalarFile", "driver_power_scalar.json", "defaultPowerScalar", 0.5, 1.5),
        DRAG("dragScalarFile", "driver_drag_scalar.json", "defaultDragScalar", 0.5, 1.5);

        final String cfgKey;
        final String defaultFile;
        final String defaultField;
        final double min;
        final double max;

        Kind(String cfgKey, String defaultFile, String defaultField, double min, double max) {
            this.cfgKey = cfgKey;
            this.defaultFile = defaultFile;
            this.defaultField = defaultField;
            this.min = min;
            this.max = max;
        }
    }

    public static final int MAX_DRIVERS = 200;
    private static final Pattern STEAM_ID = Pattern.compile("^\\d{17}$");
    private static final Pattern SAFE_FILE = Pattern.compile("^[A-Za-z0-9_.-]{1,64}\\.json$");

    // Strict on purpose: these files are plain JSON, and a lenient reader would silently drop comments when saving
    private static final tools.jackson.databind.ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    public record Table(boolean enabled, double defaultValue, Map<String, Double> drivers, String file) {}

    private final Path folder;
    private final Map<Kind, String> fileNames = new LinkedHashMap<>();

    /** @param folder the game server's folder; file names come from its server.cfg when it names them */
    public PerformanceFiles(Path folder) {
        this.folder = folder;
        for (Kind kind : Kind.values()) {
            String configured = null;
            try {
                configured = ServerConfigFile.readString(folder.resolve(ServerConfigFile.FILE_NAME), kind.cfgKey);
            } catch (IOException e) {
                // No readable server.cfg: use the default file names
            }
            fileNames.put(kind, configured != null && SAFE_FILE.matcher(configured).matches() ? configured : kind.defaultFile);
        }
    }

    public static PerformanceFiles forServer() {
        return new PerformanceFiles(GameServerPaths.root());
    }

    public Map<Kind, Table> read() throws IOException {
        Map<Kind, Table> tables = new LinkedHashMap<>();
        for (Kind kind : Kind.values()) {
            tables.put(kind, readTable(kind));
        }
        return tables;
    }

    private Table readTable(Kind kind) throws IOException {
        Path file = folder.resolve(fileNames.get(kind));
        if (!Files.exists(file)) {
            return new Table(false, kind == Kind.BALLAST ? 0 : 1.0, new LinkedHashMap<>(), fileNames.get(kind));
        }

        JsonNode root = parse(file);
        Map<String, Double> drivers = new LinkedHashMap<>();
        JsonNode node = root.path("drivers");
        if (node.isObject()) {
            node.properties().forEach(e -> {
                if (e.getValue().isNumber()) {
                    drivers.put(e.getKey(), e.getValue().asDouble());
                }
            });
        }
        return new Table(root.path("enabled").asBoolean(false), root.path(kind.defaultField).asDouble(kind == Kind.BALLAST ? 0 : 1.0),
                drivers, fileNames.get(kind));
    }

    /** @return a problem description, or null when every table is acceptable */
    public static String validate(Map<Kind, Table> tables) {
        if (tables == null || tables.isEmpty()) {
            return "No tables given";
        }
        for (var entry : tables.entrySet()) {
            Kind kind = entry.getKey();
            Table table = entry.getValue();
            String name = kind.name().toLowerCase();
            if (table == null || table.drivers() == null) {
                return "The " + name + " table is incomplete";
            }
            if (!inRange(table.defaultValue(), kind)) {
                return "The default " + name + " must be between " + kind.min + " and " + kind.max;
            }
            if (table.drivers().size() > MAX_DRIVERS) {
                return "At most " + MAX_DRIVERS + " drivers per table";
            }
            for (var driver : table.drivers().entrySet()) {
                if (!STEAM_ID.matcher(driver.getKey()).matches()) {
                    return "A Steam ID is 17 digits";
                }
                if (driver.getValue() == null || !inRange(driver.getValue(), kind)) {
                    return "Each " + name + " value must be between " + kind.min + " and " + kind.max;
                }
            }
        }
        return null;
    }

    private static boolean inRange(double value, Kind kind) {
        return !Double.isNaN(value) && !Double.isInfinite(value) && value >= kind.min && value <= kind.max;
    }

    public synchronized void write(Map<Kind, Table> tables) throws IOException {
        String problem = validate(tables);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }

        // Build every file's new content first so a problem in one leaves all of them untouched
        Map<Path, String> outputs = new LinkedHashMap<>();
        for (var entry : tables.entrySet()) {
            Kind kind = entry.getKey();
            Table table = entry.getValue();
            Path file = folder.resolve(fileNames.get(kind));
            ObjectNode root = Files.exists(file) ? (ObjectNode) parse(file) : JSON.createObjectNode();
            root.put("enabled", table.enabled());
            root.put(kind.defaultField, round(table.defaultValue()));
            ObjectNode drivers = JSON.createObjectNode();
            table.drivers().forEach((id, value) -> drivers.put(id, round(value)));
            root.set("drivers", drivers);
            outputs.put(file, JSON.writeValueAsString(root) + "\n");
        }

        for (var output : outputs.entrySet()) {
            Path file = output.getKey();
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            }
            Path temp = Files.createTempFile(folder, "perf", ".tmp");
            try {
                Files.writeString(temp, output.getValue(), StandardCharsets.UTF_8);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static JsonNode parse(Path file) throws IOException {
        try {
            JsonNode node = JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
            if (!node.isObject()) {
                throw new IOException(file.getFileName() + " is not a JSON object");
            }
            return node;
        } catch (JacksonException e) {
            throw new IOException(file.getFileName() + " isn't plain JSON, so Race Manager won't edit it");
        }
    }
}
