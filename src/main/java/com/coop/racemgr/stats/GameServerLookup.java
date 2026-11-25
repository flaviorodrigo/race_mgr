package com.coop.racemgr.stats;

import com.coop.racemgr.gameserver.GameServerProxy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lazily caches AMS2 dedicated server vehicle, vehicle-class, and track lookups for {@link AiPilotConverter}.
 * Safe when the dedicated server is offline: returns {@code null} instead of failing race history queries.
 */
@Component
public class GameServerLookup implements AiPilotConverter.Lookup {
    private final GameServerProxy gameServerProxy = new GameServerProxy();
    private final Map<Integer, String> vehicleIdToClass = new ConcurrentHashMap<>();
    private final Map<Integer, String> classIdToName = new ConcurrentHashMap<>();
    private final Map<String, String> trackIdToName = new ConcurrentHashMap<>();
    private volatile long lastRefreshMs = 0;

    @Override
    public String vehicleClassForVehicleId(Integer vehicleId) {
        if (vehicleId == null) {
            return null;
        }
        String cached = vehicleIdToClass.get(vehicleId);
        if (cached == null) {
            maybeRefresh();
            cached = vehicleIdToClass.get(vehicleId);
        }
        return cached;
    }

    @Override
    public String vehicleClassForClassId(Integer classId) {
        if (classId == null) {
            return null;
        }
        String cached = classIdToName.get(classId);
        if (cached == null) {
            maybeRefresh();
            cached = classIdToName.get(classId);
        }
        return cached;
    }

    @Override
    public String trackNameForTrackId(String trackId) {
        if (trackId == null || trackId.isBlank()) {
            return null;
        }
        String cached = trackIdToName.get(trackId);
        if (cached == null) {
            maybeRefresh();
            cached = trackIdToName.get(trackId);
        }
        return cached;
    }

    private synchronized void maybeRefresh() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs < 30_000) {
            return;
        }
        lastRefreshMs = now;
        try {
            List<?> vehicles = gameServerProxy.getVehicles();
            if (vehicles != null) {
                for (Object item : vehicles) {
                    if (item instanceof Map<?, ?> m) {
                        Integer id = intVal(m.get("id"));
                        String cls = strVal(m.get("class"));
                        if (id != null && cls != null) {
                            vehicleIdToClass.put(id, cls);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            List<?> classes = gameServerProxy.getVehicleClasses();
            if (classes != null) {
                for (Object item : classes) {
                    if (item instanceof Map<?, ?> m) {
                        Integer val = intVal(m.get("value"));
                        String name = strVal(m.get("name"));
                        if (val != null && name != null) {
                            classIdToName.put(val, name);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        try {
            List<?> tracks = gameServerProxy.getTracks();
            if (tracks != null) {
                for (Object item : tracks) {
                    if (item instanceof Map<?, ?> m && m.get("id") != null) {
                        String name = strVal(m.get("name"));
                        if (name != null) {
                            trackIdToName.put(String.valueOf(m.get("id")), name);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Integer intVal(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String strVal(Object o) {
        return o instanceof String s && !s.isBlank() ? s : null;
    }
}
