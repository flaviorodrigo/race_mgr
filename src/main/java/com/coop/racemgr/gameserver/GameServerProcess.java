package com.coop.racemgr.gameserver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Starts and stops the dedicated server as a child process on non-Windows hosts (e.g. under Wine
 * in a Linux container). The command is an argument list run directly, never through a shell.
 */
public class GameServerProcess {
    private static final long STOP_GRACE_SECONDS = 10;

    private final List<String> command;
    private final File workingDir;
    private final String exeName;
    private Process process;

    public GameServerProcess(List<String> command, File workingDir, String exeName) {
        this.command = List.copyOf(command);
        this.workingDir = workingDir;
        this.exeName = exeName;
    }

    public synchronized void start() throws IOException {
        stop();
        // Server output goes to our stdout so it shows up in `docker logs`
        process = new ProcessBuilder(command).directory(workingDir).inheritIO().start();
    }

    public synchronized boolean isRunning() {
        if (process != null && process.isAlive()) {
            return true;
        }
        return !matchingProcesses().isEmpty();
    }

    public synchronized void stop() {
        List<ProcessHandle> targets = new ArrayList<>();
        if (process != null) {
            process.descendants().forEach(targets::add);
            targets.add(process.toHandle());
        }
        for (ProcessHandle handle : matchingProcesses()) {
            if (targets.stream().noneMatch(t -> t.pid() == handle.pid())) {
                targets.add(handle);
            }
        }
        targets.forEach(ProcessHandle::destroy);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STOP_GRACE_SECONDS);
        while (System.nanoTime() < deadline && targets.stream().anyMatch(ProcessHandle::isAlive)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        targets.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        process = null;
    }

    // Processes (e.g. `wine DedicatedServerCmd.exe`) with the server executable as an argument
    private List<ProcessHandle> matchingProcesses() {
        if (exeName == null || exeName.isBlank()) {
            return List.of();
        }
        String wanted = exeName.toLowerCase();
        long self = ProcessHandle.current().pid();
        List<ProcessHandle> matches = new ArrayList<>();
        ProcessHandle.allProcesses()
                .filter(h -> h.pid() != self && h.isAlive())
                .filter(h -> h.info().arguments()
                        .map(args -> Arrays.stream(args).anyMatch(a -> a.toLowerCase().endsWith(wanted)))
                        .orElse(false))
                .forEach(matches::add);
        return matches;
    }
}
