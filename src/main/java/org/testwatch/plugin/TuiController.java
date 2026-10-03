package org.testwatch.plugin;

import java.io.IOException;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.testwatch.plugin.model.FileChangeEvent;
import org.testwatch.plugin.model.WatchEventType;

/** Reads terminal commands without taking ownership of the Maven JVM. */
public class TuiController extends Thread {
    private final TriggerQueue queue;
    private final TuiRenderer renderer;
    private final Runnable quit;
    private volatile boolean running = true;

    public TuiController(TriggerQueue queue, TuiRenderer renderer, Runnable quit) {
        super("tui-controller");
        setDaemon(true);
        this.queue = queue;
        this.renderer = renderer;
        this.quit = quit;
    }

    public void shutdown() { running = false; interrupt(); }

    @Override public void run() {
        Terminal terminal = renderer.getTerminal();
        if (!renderer.isActive() || terminal == null) return;
        terminal.handle(Terminal.Signal.INT, signal -> quit.run());
        NonBlockingReader reader = terminal.reader();
        try {
            while (running && !isInterrupted()) {
                int key = reader.read(100);
                if (key == -1) break;
                if (key == NonBlockingReader.READ_EXPIRED) continue;
                if (key == 'q' || key == 'Q' || key == 3) {
                    quit.run();
                    break;
                }
                if (key == 'r' || key == 'R') queue.enqueue(new FileChangeEvent(WatchEventType.ALL));
                if (key == 'f' || key == 'F') queue.enqueue(new FileChangeEvent(WatchEventType.FAILED));
            }
        } catch (IOException e) {
            if (running) renderer.printOutputLine("[test-watch] Keyboard input closed: " + e.getMessage());
        }
    }
}
