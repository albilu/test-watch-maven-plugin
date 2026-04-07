package com.example.plugin;

import com.example.plugin.model.FileChangeEvent;
import com.example.plugin.model.WatchEventType;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Reads keyboard input from JLine's Terminal and posts synthetic
 * FileChangeEvents to the TriggerQueue.
 *
 * Keys:
 * r / R - rerun all tests
 * f / F - rerun last-failed tests
 * q / Q - quit
 *
 * Uses JLine's NonBlockingReader for cross-platform raw key reading
 * (no stty hacking required).
 */
public class TuiController extends Thread {

    private static final Logger LOG = Logger.getLogger(TuiController.class.getName());

    private final TriggerQueue triggerQueue;
    private final TuiRenderer renderer;
    private volatile boolean running = true;

    public TuiController(TriggerQueue triggerQueue, TuiRenderer renderer) {
        super("tui-controller");
        setDaemon(true);
        this.triggerQueue = triggerQueue;
        this.renderer = renderer;
    }

    public void shutdown() {
        running = false;
        interrupt();
    }

    @Override
    public void run() {
        Terminal terminal = renderer.getTerminal();
        if (terminal == null)
            return;

        NonBlockingReader reader = terminal.reader();
        try {
            while (running && !isInterrupted()) {
                int ch = reader.read(100); // 100ms timeout to check running flag
                if (ch == -1)
                    continue; // timeout or EOF
                if (ch == -2)
                    continue; // timeout
                handleKey((char) ch);
            }
        } catch (IOException e) {
            if (running)
                LOG.warning("TuiController I/O error: " + e.getMessage());
        }
    }

    private void handleKey(char key) {
        if (key == 'r' || key == 'R') {
            renderer.printOutputLine("\u001B[36m[test-watch] Rerunning all tests...\u001B[0m");
            triggerQueue.enqueue(new FileChangeEvent(WatchEventType.ALL));
        } else if (key == 'f' || key == 'F') {
            renderer.printOutputLine("\u001B[36m[test-watch] Rerunning failed tests...\u001B[0m");
            triggerQueue.enqueue(new FileChangeEvent(WatchEventType.FAILED));
        } else if (key == 'q' || key == 'Q' || key == '\u0003' /* Ctrl+C */) {
            renderer.printOutputLine("\u001B[36m[test-watch] Stopping.\u001B[0m");
            renderer.cleanup();
            System.exit(0);
        }
        // other keys ignored
    }
}
