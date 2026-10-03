package org.testwatch.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testwatch.plugin.model.TriggerInfo;
import org.testwatch.plugin.model.WatchEventType;
import static org.junit.jupiter.api.Assertions.*;

class FileWatcherRegressionTest {
    @TempDir Path base;

    @Test void discoversAMissingSourceRootWhenItAppears() throws Exception {
        exercise(false, false, "**/*.java");
    }

    @Test void movingAPopulatedDirectoryTriggersItsExistingFiles() throws Exception {
        exercise(true, true, "**/*.java");
    }

    @Test void projectRelativeIncludesMatchRegisteredAbsolutePaths() throws Exception {
        exercise(true, false, "src/**/*.java");
    }

    @Test void sourceRootIsStillWatchedAfterDeletionAndRecreation() throws Exception {
        Path root = Files.createDirectories(base.resolve("src"));
        TriggerQueue queue = new TriggerQueue();
        LinkedBlockingQueue<TriggerInfo> observed = new LinkedBlockingQueue<>();
        queue.setOnChange(() -> observed.addAll(queue.getVisibleTriggers()));
        FileWatcherService watcher = new FileWatcherService(List.of(root), List.of("**/*.java"), List.of(), queue, 20);
        try {
            watcher.start();
            watcher.awaitReady();
            Files.delete(root);
            TriggerInfo deletion = observed.poll(3, TimeUnit.SECONDS);
            assertNotNull(deletion);
            assertEquals(WatchEventType.ALL, deletion.getEvent().getType());
            queue.setOnChange(null);
            queue.markDone(deletion.getId());
            observed.clear();
            queue.setOnChange(() -> observed.addAll(queue.getVisibleTriggers()));

            Path test = Files.createDirectories(root).resolve("RootTest.java");
            Files.writeString(test, "class RootTest {}");
            TriggerInfo created = observed.poll(3, TimeUnit.SECONDS);
            assertNotNull(created);
            assertTrue(created.getEvent().getChangedFiles().contains(test));
            queue.setOnChange(null);
            queue.markDone(created.getId());
            observed.clear();
            queue.setOnChange(() -> observed.addAll(queue.getVisibleTriggers()));

            Files.writeString(test, "class RootTest { int changed; }");
            TriggerInfo edited = observed.poll(3, TimeUnit.SECONDS);
            assertNotNull(edited);
            assertTrue(edited.getEvent().getChangedFiles().contains(test));
        } finally {
            watcher.shutdown();
            watcher.join(1000);
        }
        assertFalse(watcher.isAlive());
    }

    private void exercise(boolean existing, boolean move, String include) throws Exception {
        Path root = base.resolve("src");
        if (existing) Files.createDirectories(root);
        TriggerQueue queue = new TriggerQueue();
        LinkedBlockingQueue<TriggerInfo> observed = new LinkedBlockingQueue<>();
        queue.setOnChange(() -> observed.addAll(queue.getVisibleTriggers()));
        FileWatcherService watcher = new FileWatcherService(List.of(root), List.of(include), List.of(), queue, 20);
        try {
            watcher.start();
            watcher.awaitReady();
            Path destination = root.resolve("test/java/NewTest.java");
            if (move) {
                Path incoming = Files.createDirectories(base.resolve("incoming/java"));
                Files.writeString(incoming.resolve("NewTest.java"), "class NewTest {}");
                Files.move(incoming.getParent(), root.resolve("test"));
            } else {
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, "class NewTest {}");
            }
            TriggerInfo trigger = observed.poll(3, TimeUnit.SECONDS);
            assertNotNull(trigger);
            assertTrue(trigger.getEvent().getChangedFiles().contains(destination));
        } finally {
            watcher.shutdown();
            watcher.join(1000);
        }
        assertFalse(watcher.isAlive());
    }
}
