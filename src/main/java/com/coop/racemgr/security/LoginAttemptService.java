package com.coop.racemgr.security;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal in-memory brute-force protection for the login endpoint: after MAX_FAILURES failed
 * attempts from one client address within WINDOW_MILLIS, further attempts are refused until
 * the oldest failure ages out of the window.
 */
@Component
public class LoginAttemptService {
    static final int MAX_FAILURES = 5;
    static final long WINDOW_MILLIS = 15 * 60 * 1000L;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final ConcurrentHashMap<String, Deque<Long>> failures = new ConcurrentHashMap<>();

    public boolean isBlocked(String client) {
        Deque<Long> attempts = failures.get(client);
        if (attempts == null) {
            return false;
        }
        synchronized (attempts) {
            prune(attempts);
            return attempts.size() >= MAX_FAILURES;
        }
    }

    public void recordFailure(String client) {
        if (failures.size() >= MAX_TRACKED_CLIENTS) {
            failures.values().removeIf(d -> {
                synchronized (d) {
                    prune(d);
                    return d.isEmpty();
                }
            });
        }
        Deque<Long> attempts = failures.computeIfAbsent(client, k -> new ArrayDeque<>());
        synchronized (attempts) {
            prune(attempts);
            attempts.addLast(System.currentTimeMillis());
        }
    }

    public void recordSuccess(String client) {
        failures.remove(client);
    }

    private void prune(Deque<Long> attempts) {
        long cutoff = System.currentTimeMillis() - WINDOW_MILLIS;
        while (!attempts.isEmpty() && attempts.peekFirst() < cutoff) {
            attempts.removeFirst();
        }
    }
}
