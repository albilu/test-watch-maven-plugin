package com.example.plugin;

import com.example.plugin.model.FileChangeEvent;
import com.example.plugin.model.WatchEventType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class FileWatcherServiceTest {

    @TempDir
    Path watchDir;

    @Test
    void fileChange_postsEventWithinDebounceWindow() throws Exception {
        LinkedBlockingQueue<FileChangeEvent> queue = new LinkedBlockingQueue<>();
        // Use a short debounce (50ms) to keep tests fast
        FileWatcherService svc = new FileWatcherService(
            List.of(watchDir), List.of("**/*.java"), List.of(), queue, 50
        );
        svc.start();

        // Give watcher time to register before creating the file
        Thread.sleep(200);

        // Create a .java file in the watched dir
        Path javaFile = watchDir.resolve("Foo.java");
        Files.writeString(javaFile, "class Foo {}");

        // Should receive event within 2 seconds
        FileChangeEvent event = queue.poll(2, TimeUnit.SECONDS);
        assertNotNull(event, "Expected a FileChangeEvent but none arrived");
        assertEquals(WatchEventType.CHANGED, event.getType());
        assertTrue(event.getChangedFiles().stream()
            .anyMatch(p -> p.getFileName().toString().equals("Foo.java")));

        svc.interrupt();
    }

    @Test
    void excludedFile_doesNotPostEvent() throws Exception {
        LinkedBlockingQueue<FileChangeEvent> queue = new LinkedBlockingQueue<>();
        FileWatcherService svc = new FileWatcherService(
            List.of(watchDir), List.of("**/*.java"), List.of("**/excluded/**"), queue, 50
        );
        svc.start();

        // Create excluded directory and file
        Path excluded = watchDir.resolve("excluded");
        Files.createDirectories(excluded);
        Files.writeString(excluded.resolve("Ignored.java"), "class Ignored {}");

        // Wait; no event should arrive
        FileChangeEvent event = queue.poll(400, TimeUnit.MILLISECONDS);
        assertNull(event, "Excluded file should not produce an event");

        svc.interrupt();
    }
}
