package org.testwatch.plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.testwatch.plugin.model.FileChangeEvent;
import org.testwatch.plugin.model.TestRunResult;
import org.testwatch.plugin.model.TestRunResult.Status;
import org.testwatch.plugin.model.TriggerInfo;
import org.testwatch.plugin.model.WatchEventType;

/** Owns one watch session for all selected reactor projects. */
public class WatchLoop {
    private final MavenProject project;
    private final MavenSession session;
    private final boolean initialRun;
    private final boolean smartSelection;
    private final boolean parallel;
    private final List<String> includes;
    private final List<String> excludes;
    private final String testPattern;
    private final long debounceMillis;

    public WatchLoop(MavenProject project, boolean initialRun, boolean smartSelection, boolean parallel,
            List<String> includes, List<String> excludes, String testPattern, long debounceMillis) {
        this(project, null, initialRun, smartSelection, parallel, includes, excludes, testPattern, debounceMillis);
    }

    public WatchLoop(MavenProject project, MavenSession session, boolean initialRun, boolean smartSelection,
            boolean parallel, List<String> includes, List<String> excludes, String testPattern, long debounceMillis) {
        this.project = project;
        this.session = session;
        this.initialRun = initialRun;
        this.smartSelection = smartSelection;
        this.parallel = parallel;
        this.includes = includes;
        this.excludes = excludes;
        this.testPattern = testPattern;
        this.debounceMillis = debounceMillis;
    }

    public void run() throws IOException, InterruptedException {
        String ciMaximum = System.getProperty("testWatch.ciMaxRunSeconds");
        long timeout = ciMaximum == null ? 0 : Long.parseLong(ciMaximum);
        if (timeout < 0) throw new IllegalArgumentException("ciMaxRunSeconds must not be negative");
        List<MavenProject> projects = session == null || session.getProjects() == null
                ? List.of(project) : session.getProjects();
        List<ProjectLayout> layouts = projects.stream().map(p -> new ProjectLayout(p, session)).collect(Collectors.toList());
        Map<Path, Path> watchRoots = new LinkedHashMap<>();
        List<Path> sourceRoots = new ArrayList<>(), testRoots = new ArrayList<>();
        for (ProjectLayout layout : layouts) {
            sourceRoots.addAll(layout.sources);
            testRoots.addAll(layout.testSources);
            layout.sources.forEach(root -> watchRoots.put(root, layout.basedir));
            layout.testSources.forEach(root -> watchRoots.put(root, layout.basedir));
        }
        List<String> patterns = Arrays.stream(testPattern.split(",")).map(String::trim)
                .filter(p -> !p.isEmpty()).collect(Collectors.toList());
        MavenTestRunner runner = new MavenTestRunner(project, session, layouts, parallel);
        TriggerQueue queue = new TriggerQueue();
        TuiRenderer renderer = new TuiRenderer();
        FileWatcherService watcher = new FileWatcherService(watchRoots, includes, excludes, queue, debounceMillis);
        AtomicBoolean stopping = new AtomicBoolean();
        Thread owner = Thread.currentThread();
        Runnable stop = () -> {
            if (stopping.compareAndSet(false, true)) {
                owner.interrupt();
                runner.cancel();
            }
        };
        TuiController controller = new TuiController(queue, renderer, stop);
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "watch-timeout");
            thread.setDaemon(true);
            return thread;
        });
        Thread shutdownHook = new Thread(() -> {
            stopping.set(true);
            runner.cancel();
            watcher.shutdown();
            controller.shutdown();
            renderer.cleanup();
        }, "watch-shutdown");
        boolean hookRegistered = false;
        try {
            renderer.setTriggerQueue(queue);
            renderer.setup();
            runner.setOutputSink(renderer::printOutputLine);
            runner.setInteractiveOutput(renderer.isActive());
            runner.setSummaryEnabled(false);
            queue.setOnChange(renderer::refreshBottomPanel);
            queue.setOnEnqueue(runner::cancel);
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            hookRegistered = true;
            watcher.start();
            watcher.awaitReady();
            if (renderer.isActive()) controller.start();
            if (ciMaximum != null) timer.schedule(stop, timeout, TimeUnit.SECONDS);
            DependencyGraph graph = buildGraph(layouts, patterns, renderer);
            if (initialRun) queue.enqueue(new FileChangeEvent(WatchEventType.ALL));
            while (!stopping.get()) {
                TriggerInfo trigger = queue.takeNext();
                FileChangeEvent event = trigger.getEvent();
                Set<String> tests = Set.of();
                if (event.getType() == WatchEventType.FAILED) {
                    tests = runner.getLastFailedFqns();
                    if (tests.isEmpty()) renderer.printOutputLine("[test-watch] No failed tests recorded — running all.");
                } else if (event.getType() == WatchEventType.CHANGED && smartSelection) {
                    TestSelector.Result selection = TestSelector.select(event.getChangedFiles(), graph,
                            sourceRoots, testRoots, testPattern);
                    if (!selection.isAll()) tests = new LinkedHashSet<>(selection.getTestFqns());
                }
                renderer.updateResult(TestRunResult.running());
                if (tests.isEmpty()) runner.runAll(); else runner.run(tests);
                TestRunResult result = runner.getLastResult();
                renderer.updateResult(result);
                queue.markDone(trigger.getId());
                if (stopping.get()) break;
                if (result.getStatus() == Status.BUILD_ERROR || result.getStatus() == Status.CANCELLED) {
                    graph = DependencyGraph.empty();
                } else {
                    graph = buildGraph(layouts, patterns, renderer);
                }
            }
        } catch (InterruptedException e) {
            if (!stopping.get()) throw e;
        } finally {
            boolean intentionalStop = stopping.get();
            stopping.set(true);
            queue.setOnEnqueue(null);
            queue.setOnChange(null);
            timer.shutdownNow();
            watcher.shutdown();
            controller.shutdown();
            runner.cancel();
            renderer.cleanup();
            if (hookRegistered) {
                try { Runtime.getRuntime().removeShutdownHook(shutdownHook); }
                catch (IllegalStateException ignored) { }
            }
            if (intentionalStop) Thread.interrupted();
        }
    }

    private DependencyGraph buildGraph(List<ProjectLayout> layouts, List<String> patterns, TuiRenderer renderer) {
        try {
            return DependencyGraph.build(layouts, patterns);
        } catch (IOException | RuntimeException e) {
            renderer.printOutputLine("[test-watch] Dependency analysis unavailable; running all tests on source changes: " + e.getMessage());
            return DependencyGraph.empty();
        }
    }
}
