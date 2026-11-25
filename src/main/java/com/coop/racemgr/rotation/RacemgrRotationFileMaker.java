package com.coop.racemgr.rotation;

import com.coop.racemgr.gameserver.GameServerPaths;
import com.coop.racemgr.gameserver.SessionSchema;
import com.coop.racemgr.model.RaceRotation;
import com.coop.racemgr.utils.RacemgrUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// NOTE: Jackson will only work/serialize fields that are either public
// or have a public getter methods
public class RacemgrRotationFileMaker {
    final public int version;
    public Object config;

    public RacemgrRotationFileMaker(RaceRotation raceRotation) {
        this(raceRotation, null);
    }

    public RacemgrRotationFileMaker(RaceRotation raceRotation, SessionSchema.Schema schema) {
        this.version = 7;
        this.config = schema == null ? raceRotation.config : toServerReadyConfig(raceRotation.config, schema);
    }

    /**
     * Converts string enum names and comma-separated {@code Flags} into the numeric values AMS2DS uses internally,
     * so {@code sms_rotate} does not skip or mis-parse fields like {@code ManualPitStops: "Manual"} or {@code DamageType: "FULL"}.
     * Leaves MongoDB's {@code RaceRotation.config} untouched.
     */
    @SuppressWarnings("unchecked")
    public static Object toServerReadyConfig(Object configObj, SessionSchema.Schema schema) {
        if (!(configObj instanceof Map<?, ?> rawConfig) || schema == null) {
            return configObj;
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : rawConfig.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object val = e.getValue();
            if (("defaultConfig".equals(key) || "default".equals(key)) && val instanceof Map<?, ?> setup) {
                copy.put(key, convertSetup((Map<?, ?>) setup, schema));
            } else if ("rotation".equals(key) && val instanceof List<?> rounds) {
                List<Object> convertedRounds = new ArrayList<>(rounds.size());
                for (Object r : rounds) {
                    convertedRounds.add(r instanceof Map<?, ?> setup ? convertSetup(setup, schema) : r);
                }
                copy.put(key, convertedRounds);
            } else {
                copy.put(key, val);
            }
        }
        return copy;
    }

    private static Map<String, Object> convertSetup(Map<?, ?> setup, SessionSchema.Schema schema) {
        Map<String, SessionSchema.Attribute> attrByName = new LinkedHashMap<>();
        for (SessionSchema.Attribute a : schema.attributes()) {
            attrByName.put(a.name(), a);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : setup.entrySet()) {
            String key = String.valueOf(e.getKey());
            Object val = e.getValue();
            SessionSchema.Attribute attr = attrByName.get(key);

            if ("Flags".equals(key) && val instanceof String flagStr) {
                out.put(key, encodeFlags(flagStr, schema.flags()));
            } else if (attr != null && "enum".equals(attr.kind()) && attr.enumName() != null && val instanceof String strVal) {
                List<SessionSchema.Option> options = schema.enums().get(attr.enumName());
                Long resolved = findOptionValue(strVal, options);
                out.put(key, resolved != null ? shrinkLong(resolved) : val);
            } else {
                out.put(key, val);
            }
        }
        return out;
    }

    private static int encodeFlags(String flagStr, List<SessionSchema.Option> flagOptions) {
        long sum = 0;
        for (String part : flagStr.split(",")) {
            String name = part.trim();
            if (name.isEmpty() || "FORCE_SAME_VEHICLE_CLASS".equalsIgnoreCase(name) || "FORCE_IDENTICAL_VEHICLES".equalsIgnoreCase(name)) {
                continue;
            }
            Long bit = findOptionValue(name, flagOptions);
            if (bit != null) {
                sum += bit;
            }
        }
        return (int) sum;
    }

    private static Long findOptionValue(String name, List<SessionSchema.Option> options) {
        if (options == null) {
            return null;
        }
        for (SessionSchema.Option o : options) {
            if (o.name().equalsIgnoreCase(name)) {
                return o.value();
            }
        }
        return null;
    }

    private static Object shrinkLong(long v) {
        if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
            return (int) v;
        }
        return v;
    }

    private void deleteCachedRotationFile() {
        File file = GameServerPaths.cachedRotationFile().toFile();
        if (file.delete()) {
            System.out.println("Game Server cached rotation deleted successfully");
        } else {
            System.out.println("Game Server cached rotation");
        }
    }

    public void writeGeneratedRotationFile() throws IOException {
        File rotationFileToUpdate = GameServerPaths.defaultRotationFile().toFile();

        ObjectMapper mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

        // Convert config object to JSON file
        mapper.writerWithDefaultPrettyPrinter().writeValue(rotationFileToUpdate, this);
        // The game server expects the default config to use the property name "default"
        // which is a Java reserved word. So we call it defaultConfig in the class and update
        // once it has been serialized in the file itself.
        RacemgrUtils.replaceStringInFile(rotationFileToUpdate, "defaultConfig", "default");

        // Once a new file has been written we must remove the active/cached one and reboot
        // the game server for this to take effect.
        this.deleteCachedRotationFile();
    }
}
