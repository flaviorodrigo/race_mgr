package com.coop.racemgr.rotation;

import java.util.List;
import java.util.Map;

/**
 * A rotation as a Lua table, the form the racemgr_rotate addon reads (lua_config/racemgr_rotation.lua):
 * {@code return { version = N, default = { ... }, rotation = { { ... }, ... } }}.
 * Only tables, text, numbers and booleans are written, so the file can't carry anything the addon would run; the
 * addon reads it with an empty environment on top of that.
 */
public final class RotationLuaFile {
    private static final int MAX_DEPTH = 6;

    private RotationLuaFile() {}

    /** @param config a rotation's config: {@code default} (or {@code defaultConfig}) and {@code rotation} */
    public static String render(Object config, long version) {
        if (!(config instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("The rotation has no configuration");
        }
        Object defaults = map.containsKey("default") ? map.get("default") : map.get("defaultConfig");
        Object rotation = map.get("rotation");
        if (!(defaults instanceof Map<?, ?>) || !(rotation instanceof List<?>)) {
            throw new IllegalArgumentException("The rotation needs a default setup and a list of rounds");
        }

        StringBuilder out = new StringBuilder("return {\n");
        out.append("  version = ").append(version).append(",\n");
        out.append("  default = ");
        value(out, defaults, 1);
        out.append(",\n  rotation = ");
        value(out, rotation, 1);
        out.append("\n}\n");
        return out.toString();
    }

    private static void value(StringBuilder out, Object value, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("The rotation is nested too deeply");
        }
        if (value instanceof Map<?, ?> map) {
            out.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                out.append(first ? " " : ", ");
                first = false;
                out.append('[');
                text(out, String.valueOf(entry.getKey()));
                out.append("] = ");
                value(out, entry.getValue(), depth + 1);
            }
            out.append(first ? "}" : " }");
        } else if (value instanceof List<?> list) {
            out.append("{");
            boolean first = true;
            for (Object item : list) {
                if (item == null) {
                    continue;
                }
                out.append(first ? " " : ", ");
                first = false;
                value(out, item, depth + 1);
            }
            out.append(first ? "}" : " }");
        } else if (value instanceof Boolean b) {
            out.append(b);
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("A number in the rotation is not valid");
            }
            // Whole numbers stay integers: Lua's integer and float are different and the game takes integers
            out.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d));
        } else if (value instanceof Number n) {
            out.append(n.longValue());
        } else {
            text(out, String.valueOf(value));
        }
    }

    // A double-quoted Lua string: anything that isn't plain printable ASCII is written as a decimal escape
    private static void text(StringBuilder out, String s) {
        out.append('"');
        byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c == '"' || c == '\\') {
                out.append('\\').append((char) c);
            } else if (c >= 0x20 && c < 0x7F) {
                out.append((char) c);
            } else {
                out.append('\\').append(String.format("%03d", c));
            }
        }
        out.append('"');
    }
}
