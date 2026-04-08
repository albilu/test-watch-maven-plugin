package org.testwatch.plugin;

import org.testwatch.plugin.model.FileChangeEvent;
import org.testwatch.plugin.model.TriggerInfo;
import org.testwatch.plugin.model.WatchEventType;;
import org.testwatch.plugin.model.TriggerInfo;
import org.testwatch.plugin.model.WatchEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testwatch.plugin.FileWatcherService;
import org.testwatch.plugin.TriggerQueue;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class FileWatcherServiceTest {

    @TempDir
    Path watchDir;

    @Test
    void fileChange_postsEventWithinDebounceWindow() throws Exception {
        TriggerQueue triggerQueue = new TriggerQueue();
        // Use a blocking queue to observe triggers as they arrive
        BlockingQueue<TriggerInfo> received = new LinkedBlockingQueue<>();
        triggerQueue.setOnChange(() -> {
            for (TriggerInfo t : triggerQueue.getVisibleTriggers()) {
                received.offer(t);
            }
        });

        FileWatcherService svc = new FileWatcherService(
                List.of(watchDir), List.of("**/*.java"), List.of(), triggerQueue, 50);
        svc.start();

        // Give watcher time to register before creating the file
        Thread.sleep(200);

        // Create a .java file in the watched dir
        Path javaFile = watchDir.resolve("Foo.java");
        Files.writeString(javaFile, "class Foo {}");

        // Should receive trigger within 2 seconds
        TriggerInfo trigger = received.poll(2, TimeUnit.SECONDS);
        assertNotNull(trigger, "Expected a TriggerInfo but none arrived");
        FileChangeEvent event = trigger.getEvent();
        assertEquals(WatchEventType.CHANGED, event.getType());
        assertTrue(event.getChangedFiles().stream()
                .anyMatch(p -> p.getFileName().toString().equals("Foo.java")));

        svc.interrupt();
    }

    @Test
    void excludedFile_doesNotPostEvent() throws Exception {
        TriggerQueue triggerQueue = new TriggerQueue();
        BlockingQueue<TriggerInfo> received = new LinkedBlockingQueue<>();
        triggerQueue.setOnChange(() -> {
            for (TriggerInfo t : triggerQueue.getVisibleTriggers()) {
                received.offer(t);
            }
        });

        FileWatcherService svc = new FileWatcherService(
                List.of(watchDir), List.of("**/*.java"), List.of("**/excluded/**"), triggerQueue, 50);
        svc.start();

        // Create excluded directory and file
        Path excluded = watchDir.resolve("excluded");
        Files.createDirectories(excluded);
        Files.writeString(excluded.resolve("Ignored.java"), "class Ignored {}");

        // Wait; no event should arrive
        TriggerInfo trigger = received.poll(400, TimeUnit.MILLISECONDS);
        assertNull(trigger, "Excluded file should not produce an event");

        svc.interrupt();
    }
}
