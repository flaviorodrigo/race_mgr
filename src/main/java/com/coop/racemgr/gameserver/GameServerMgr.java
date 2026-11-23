package com.coop.racemgr.gameserver;

import com.coop.racemgr.RacemgrApplication;
import com.coop.racemgr.utils.WindowsUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.*;
import java.util.Arrays;

@Component
public class GameServerMgr implements Runnable {
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    // Non-Windows (e.g. Linux + Wine) only: the command that launches the server. Override with
    // RM_GAMESERVER_LAUNCH_CMD (whitespace separated, run without a shell), default "wine DedicatedServerCmd.exe".
    private static GameServerProcess linuxProcess;

    private static final Logger logger = LogManager.getLogger(RacemgrApplication.class);

    /**
     * Calling run() will actually run the server on the current thread. Call start() to
     * get a background thread instead.
     */
    public void run() {
        try {
            GameServerMgr.start();
        } catch (InterruptedException e) {
            logger.error(e);
            e.printStackTrace();
        } catch (IOException e) {
            logger.error(e);
            e.printStackTrace();
        }
    }

    private static synchronized GameServerProcess linuxProcess() {
        if (linuxProcess == null) {
            String configured = System.getenv("RM_GAMESERVER_LAUNCH_CMD");
            String[] command = (configured == null || configured.isBlank())
                    ? new String[]{"wine", GameServerPaths.EXE_NAME}
                    : configured.trim().split("\\s+");
            linuxProcess = new GameServerProcess(Arrays.asList(command), GameServerPaths.root().toFile(),
                    GameServerPaths.EXE_NAME);
            // Don't leave an orphaned game server behind if Race Manager exits
            Runtime.getRuntime().addShutdownHook(new Thread(linuxProcess::stop));
        }
        return linuxProcess;
    }

    public static void start() throws InterruptedException, IOException {
        if (isRunning() == true) {
            logger.info("External game server already running, forcing restart!");
            GameServerMgr.stop();
            Thread.sleep(1000); // Avoid race, let the thread die before starting
        }

        try {
            logger.info("Starting the external game server!");
            if (IS_WINDOWS) {
                String[] command = { GameServerPaths.executable().toString() };
                String[] commandArgs = {};
                Runtime.getRuntime().exec(command, commandArgs, GameServerPaths.root().toFile());
            } else {
                linuxProcess().start();
            }
        } catch (IOException | IllegalStateException e) {
            logger.error(e);
            e.printStackTrace();
        }
    }

    public static void restart() throws IOException, InterruptedException {
        GameServerMgr.start();
    }

    @PreDestroy
    public static void stop() throws IOException {
        if (isRunning() == true) {
            logger.info("Stopping the external game server!");
            if (IS_WINDOWS) {
                var pids = WindowsUtils.getPidsByName(GameServerPaths.EXE_NAME);
                if (pids.size() > 0) {
                    WindowsUtils.killProcess(GameServerPaths.EXE_NAME);
                }
            } else {
                linuxProcess().stop();
            }
        } else {
            logger.info("External game server not running, nothing to stop.");
        }
    }

    public static boolean isRunning() throws IOException {
        if (IS_WINDOWS) {
            return WindowsUtils.isProcessRunning(GameServerPaths.EXE_NAME);
        }
        try {
            return linuxProcess().isRunning();
        } catch (IllegalStateException e) {
            // RM_PATH_TO_DEDICATED_SERVER not set
            return false;
        }
    }
}
