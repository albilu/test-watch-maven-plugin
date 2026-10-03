package org.testwatch.plugin;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.testwatch.plugin.model.FileChangeEvent;
import org.testwatch.plugin.model.WatchEventType;

/** Watches source roots, including roots and populated subtrees created after startup. */
public class FileWatcherService extends Thread {
    private static final Logger LOG = Logger.getLogger(FileWatcherService.class.getName());
    private final Map<Path, Path> roots = new LinkedHashMap<>();
    private final PathPatterns includes;
    private final PathPatterns excludes;
    private final TriggerQueue queue;
    private final long debounceMillis;
    private final CountDownLatch ready = new CountDownLatch(1);
    private final Map<Path, WatchKey> registered = new LinkedHashMap<>();
    private volatile WatchService service;
    private volatile IOException startupError;
    private volatile boolean stopping;

    public FileWatcherService(List<Path> watchRoots, List<String> includes, List<String> excludes,
            TriggerQueue queue, long debounceMillis) {
        this(defaultRoots(watchRoots), includes, excludes, queue, debounceMillis);
    }

    FileWatcherService(Map<Path, Path> watchRoots, List<String> includes, List<String> excludes,
            TriggerQueue queue, long debounceMillis) {
        super("file-watcher");
        setDaemon(true);
        if (debounceMillis < 0) throw new IllegalArgumentException("debounceMillis must not be negative");
        watchRoots.forEach((root, base) -> roots.put(root.toAbsolutePath().normalize(), base.toAbsolutePath().normalize()));
        this.includes = new PathPatterns(includes);
        this.excludes = new PathPatterns(excludes);
        this.queue = queue;
        this.debounceMillis = debounceMillis;
    }

    private static Map<Path, Path> defaultRoots(List<Path> roots) {
        Map<Path, Path> result = new LinkedHashMap<>();
        for (Path root : roots) {
            Path absolute = root.toAbsolutePath().normalize();
            result.put(absolute, "src".equals(absolute.getFileName().toString()) ? absolute.getParent() : absolute);
        }
        return result;
    }

    public void awaitReady() throws InterruptedException, IOException {
        ready.await();
        if (startupError != null) throw startupError;
    }

    public void shutdown() {
        stopping = true;
        interrupt();
        WatchService current = service;
        if (current != null) try { current.close(); } catch (IOException ignored) { }
    }

    @Override public void run() {
        try (WatchService watcher = FileSystems.getDefault().newWatchService()) {
            service = watcher;
            registerRoots(watcher, null);
            ready.countDown();
            Set<Path> pending = new LinkedHashSet<>();
            long lastChange = 0;
            while (!stopping && !isInterrupted()) {
                WatchKey key = watcher.poll(20, TimeUnit.MILLISECONDS);
                if (key != null) {
                    for (var event : key.pollEvents()) {
                        if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                            queue.enqueue(new FileChangeEvent(WatchEventType.ALL));
                            pending.clear();
                            continue;
                        }
                        Path path = ((Path) key.watchable()).resolve((Path) event.context()).toAbsolutePath().normalize();
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(path)) {
                            if (relevantDirectory(path)) registerRoots(watcher, pending);
                        } else if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE && registered.containsKey(path)) {
                            registered.entrySet().removeIf(entry -> {
                                if (!entry.getKey().startsWith(path)) return false;
                                entry.getValue().cancel();
                                return true;
                            });
                            if (relevantDirectory(path)) queue.enqueue(new FileChangeEvent(WatchEventType.ALL));
                        } else if (matches(path)) {
                            if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                                queue.enqueue(new FileChangeEvent(WatchEventType.ALL));
                            } else pending.add(path);
                        }
                        if (!pending.isEmpty()) lastChange = System.nanoTime();
                    }
                    if (!key.reset() && registered.remove((Path) key.watchable(), key)) {
                        // An invalid directory key can arrive before its parent's delete event.
                        // Retain the change and move observation to the nearest surviving ancestor.
                        queue.enqueue(new FileChangeEvent(WatchEventType.ALL));
                        registerRoots(watcher, pending);
                        if (!pending.isEmpty()) lastChange = System.nanoTime();
                    }
                }
                if (!pending.isEmpty() && System.nanoTime() - lastChange >= TimeUnit.MILLISECONDS.toNanos(debounceMillis)) {
                    queue.enqueue(new FileChangeEvent(WatchEventType.CHANGED, new LinkedHashSet<>(pending)));
                    pending.clear();
                }
            }
        } catch (ClosedWatchServiceException e) {
            if (!stopping) LOG.warning("Source watcher closed unexpectedly");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            startupError = e;
            if (!stopping) LOG.severe("Source watcher failed: " + e.getMessage());
        } finally {
            ready.countDown();
            service = null;
        }
    }

    private boolean relevantDirectory(Path directory) {
        return roots.keySet().stream().anyMatch(root -> directory.startsWith(root) || root.startsWith(directory));
    }

    private boolean matches(Path path) {
        for (Map.Entry<Path, Path> root : roots.entrySet()) {
            if (!path.startsWith(root.getKey())) continue;
            Path relative = root.getValue().relativize(path);
            if ((includes.isEmpty() || includes.matches(relative)) && !excluded(relative)) return true;
        }
        return false;
    }

    private boolean excluded(Path relative) {
        for (Path path = relative; path != null; path = path.getParent()) {
            if (excludes.matches(path)) return true;
        }
        return false;
    }

    private void registerRoots(WatchService watcher, Set<Path> newlyFound) throws IOException {
        for (Path root : roots.keySet()) {
            // The parent catches root deletion/recreation, or the next missing ancestor's creation.
            Path parent = root.getParent();
            while (parent != null && !Files.isDirectory(parent)) parent = parent.getParent();
            if (parent != null) register(parent, watcher);
            if (!Files.isDirectory(root)) continue;
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                private final Set<Path> newDirectories = new LinkedHashSet<>();
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                    if (register(directory, watcher)) newDirectories.add(directory);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (newlyFound != null && newDirectories.contains(file.getParent()) && matches(file)) newlyFound.add(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
                    if (!Files.exists(file)) return FileVisitResult.CONTINUE;
                    throw exception;
                }
            });
        }
    }

    private boolean register(Path directory, WatchService watcher) throws IOException {
        WatchKey previous = registered.get(directory);
        if (previous != null && previous.isValid()) return false;
        WatchKey key = directory.register(watcher, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
        registered.put(directory, key);
        return true;
    }
}
