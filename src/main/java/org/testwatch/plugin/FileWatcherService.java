package org.testwatch.plugin;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.testwatch.plugin.model.FileChangeEvent;
import org.testwatch.plugin.model.WatchEventType;

/**
 * Background thread that watches a set of directories for .java file changes,
 * debounces rapid saves, and posts FileChangeEvents to a shared queue.
 */
public class FileWatcherService extends Thread {

    private static final Logger LOG = Logger.getLogger(FileWatcherService.class.getName());

    private final List<Path> watchRoots;
    private final List<PathMatcher> includeMatchers;
    private final List<PathMatcher> excludeMatchers;
    private final TriggerQueue triggerQueue;
    private final long debounceMillis;

    /**
     * @param watchRoots     directories to watch recursively
     * @param includes       glob patterns for files to include (e.g. **&#47;*.java)
     * @param excludes       glob patterns for files to exclude (e.g.
     *                       "**&#47;target&#47;**")
     * @param triggerQueue   shared trigger queue
     * @param debounceMillis window to accumulate changes before posting (default
     *                       100)
     */
    public FileWatcherService(List<Path> watchRoots, List<String> includes,
            List<String> excludes, TriggerQueue triggerQueue,
            long debounceMillis) {
        super("file-watcher");
        setDaemon(true);
        this.watchRoots = watchRoots;
        this.includeMatchers = includes.stream()
                .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p))
                .collect(Collectors.toList());
        this.excludeMatchers = excludes.stream()
                .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p))
                .collect(Collectors.toList());
        this.triggerQueue = triggerQueue;
        this.debounceMillis = debounceMillis;
    }

    @Override
    public void run() {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            // Register all directories recursively
            for (Path root : watchRoots) {
                registerAll(root, ws);
            }

            Set<Path> pending = new LinkedHashSet<>();
            long lastChange = -1;

            while (!isInterrupted()) {
                // Use a short poll so we can flush the debounce buffer
                WatchKey key = ws.poll(20, TimeUnit.MILLISECONDS);

                if (key != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();

                        if (kind == StandardWatchEventKinds.OVERFLOW) {
                            triggerQueue.enqueue(new FileChangeEvent(WatchEventType.ALL));
                            pending.clear();
                            lastChange = -1;
                            key.reset();
                            continue;
                        }

                        @SuppressWarnings("unchecked")
                        WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                        Path dir = (Path) key.watchable();
                        Path fullPath = dir.resolve(pathEvent.context());

                        // Register new directories
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE
                                && Files.isDirectory(fullPath)) {
                            registerAll(fullPath, ws);
                        }

                        if (matches(fullPath)) {
                            pending.add(fullPath);
                            lastChange = System.currentTimeMillis();
                        }
                    }
                    key.reset();
                }

                // Flush debounce buffer if window has elapsed
                if (!pending.isEmpty() && System.currentTimeMillis() - lastChange >= debounceMillis) {
                    triggerQueue.enqueue(new FileChangeEvent(WatchEventType.CHANGED, new LinkedHashSet<>(pending)));
                    pending.clear();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.severe("FileWatcherService error: " + e.getMessage());
        }
    }

    private boolean matches(Path path) {
        // Convert to a relative-looking path for glob matching
        Path matchPath = path;
        boolean included = includeMatchers.isEmpty()
                || includeMatchers.stream().anyMatch(m -> m.matches(matchPath)
                        || m.matches(matchPath.getFileName()));
        boolean excluded = excludeMatchers.stream().anyMatch(m -> {
            // Check each path segment combination for exclude globs like **/target/**
            for (int i = 0; i <= matchPath.getNameCount(); i++) {
                if (i < matchPath.getNameCount() && m.matches(matchPath.subpath(0, i + 1)))
                    return true;
            }
            return m.matches(matchPath) || m.matches(matchPath.getFileName());
        });
        return included && !excluded;
    }

    private void registerAll(Path root, WatchService ws) throws IOException {
        if (!Files.exists(root))
            return;
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                dir.register(ws,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
