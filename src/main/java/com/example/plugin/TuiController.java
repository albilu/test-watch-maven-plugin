package com.example.plugin;

import com.example.plugin.model.FileChangeEvent;
import com.example.plugin.model.WatchEventType;

import java.io.*;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Logger;

/**
 * Reads keyboard input and posts synthetic FileChangeEvents to the queue.
 *
 * Keys:
 *   r / R  - rerun all tests (posts ALL sentinel)
 *   f / F  - rerun last-failed tests (posts FAILED sentinel)
 *   q / Q  - quit
 *
 * On Unix, opens /dev/tty and switches it to raw mode via
 * {@code stty -echo -icanon min 1 time 0} so each key fires immediately
 * without requiring Enter.  The original terminal settings are restored
 * on shutdown via a JVM shutdown hook.
 *
 * Falls back to line-buffered System.in on Windows or when /dev/tty is
 * unavailable (e.g. CI pipelines without a controlling terminal).
 */
public class TuiController extends Thread {

    private static final Logger LOG = Logger.getLogger(TuiController.class.getName());

    private static final String CYAN  = "\u001B[36m";
    private static final String RESET = "\u001B[0m";

    private final BlockingQueue<FileChangeEvent> queue;
    private volatile boolean running = true;

    /** Saved terminal settings so we can restore them on exit. */
    private String savedSttySettings = null;

    public TuiController(BlockingQueue<FileChangeEvent> queue) {
        super("tui-controller");
        setDaemon(true);
        this.queue = queue;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        printHelp();
        InputStream input = openRawInput();
        try {
            while (running && !isInterrupted()) {
                int ch = input.read();
                if (ch < 0) break; // EOF
                handleKey((char) ch);
            }
        } catch (IOException e) {
            if (running) LOG.warning("TuiController I/O error: " + e.getMessage());
        } finally {
            restoreTerminal();
            if (input != System.in) {
                try { input.close(); } catch (IOException ignored) {}
            }
        }
    }

    private void handleKey(char key) {
        if (key == 'r' || key == 'R') {
            System.out.println(CYAN + "\n[test-watch] Rerunning all tests..." + RESET);
            queue.offer(new FileChangeEvent(WatchEventType.ALL));
        } else if (key == 'f' || key == 'F') {
            System.out.println(CYAN + "\n[test-watch] Rerunning failed tests..." + RESET);
            queue.offer(new FileChangeEvent(WatchEventType.FAILED));
        } else if (key == 'q' || key == 'Q' || key == '\u0003' /* Ctrl+C */) {
            System.out.println(CYAN + "\n[test-watch] Stopping." + RESET);
            restoreTerminal();
            System.exit(0);
        }
        // other keys ignored
    }

    /**
     * Opens /dev/tty and puts the terminal in raw mode (no echo, no line
     * buffering, return immediately on 1 byte).  Saves the original settings
     * so they can be restored later.  Falls back to System.in if anything
     * goes wrong.
     */
    private InputStream openRawInput() {
        File tty = new File("/dev/tty");
        if (!tty.exists() || !tty.canRead()) {
            return System.in; // Windows or no controlling tty (CI)
        }
        // Save current stty settings
        try {
            Process save = new ProcessBuilder("stty", "-g")
                .redirectInput(tty)
                .start();
            save.waitFor();
            savedSttySettings = new String(save.getInputStream().readAllBytes()).trim();
        } catch (Exception e) {
            LOG.fine("stty -g failed, raw mode unavailable: " + e.getMessage());
            return System.in;
        }
        // Switch to raw mode: no echo, character-at-a-time, no timeout
        try {
            new ProcessBuilder("stty", "-echo", "-icanon", "min", "1", "time", "0")
                .redirectInput(tty)
                .inheritIO()   // stdout/stderr go to terminal so we see errors
                .start()
                .waitFor();
        } catch (Exception e) {
            LOG.fine("stty raw mode failed: " + e.getMessage());
            savedSttySettings = null; // nothing to restore
            return System.in;
        }
        // Register shutdown hook to always restore tty even on SIGINT
        Runtime.getRuntime().addShutdownHook(new Thread(this::restoreTerminal, "tui-restore"));
        try {
            return new FileInputStream(tty);
        } catch (IOException e) {
            restoreTerminal();
            return System.in;
        }
    }

    /** Restores the terminal to the settings captured before raw mode. */
    private void restoreTerminal() {
        String settings = savedSttySettings;
        if (settings == null || settings.isEmpty()) return;
        savedSttySettings = null; // run at most once
        try {
            File tty = new File("/dev/tty");
            new ProcessBuilder("stty", settings)
                .redirectInput(tty)
                .start()
                .waitFor();
        } catch (Exception e) {
            LOG.fine("stty restore failed: " + e.getMessage());
        }
    }

    private void printHelp() {
        System.out.println(CYAN +
            "\n[test-watch] Watching for changes." +
            "  [r] rerun all  [f] rerun failed  [q] quit" +
            RESET);
    }
}
