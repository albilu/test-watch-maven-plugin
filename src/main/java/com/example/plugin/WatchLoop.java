package com.example.plugin;

import com.example.plugin.model.FileChangeEvent;
import com.example.plugin.model.TriggerInfo;
import com.example.plugin.model.WatchEventType;
import org.apache.maven.project.MavenProject;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Core event loop shared by WatchMojo (initialRun=false) and TestMojo
 * (initialRun=true).
 */
public class WatchLoop {

    private static final Logger LOG = Logger.getLogger(WatchLoop.class.getName());
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String RESET = "\u001B[0m";

    private final MavenProject project;
    private final boolean initialRun;
    private final boolean smartSelection;
    private final boolean parallel;
    private final List<String> includes;
    private final List<String> excludes;
    private final String testPattern;
    private final long debounceMillis;

    public WatchLoop(MavenProject project, boolean initialRun,
            boolean smartSelection, boolean parallel,
            List<String> includes, List<String> excludes,
            String testPattern, long debounceMillis) {
        this.project = project;
        this.initialRun = initialRun;
        this.smartSelection = smartSelection;
        this.parallel = parallel;
        this.includes = includes;
        this.excludes = excludes;
        this.testPattern = testPattern;
        this.debounceMillis = debounceMillis;
    }

    public void run() throws IOException, InterruptedException {
        Path basedir = project.getBasedir().toPath();
        Path classesDir = basedir.resolve("target/classes");
        Path testClassesDir = basedir.resolve("target/test-classes");
        Path sourceRoot = basedir.resolve("src/main/java");
        Path testSourceRoot = basedir.resolve("src/test/java");

        List<String> patterns = Arrays.stream(testPattern.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();

        MavenTestRunner runner = new MavenTestRunner(project.getBasedir(), parallel);

        // Set up TUI renderer (JLine)
        TuiRenderer renderer = new TuiRenderer();
        TriggerQueue triggerQueue = new TriggerQueue();
        renderer.setTriggerQueue(triggerQueue);
        triggerQueue.setOnChange(renderer::refreshBottomPanel);

        try {
            renderer.setup();
        } catch (IOException e) {
            LOG.warning("Could not set up JLine terminal: " + e.getMessage() + " — using plain output");
        }

        // Route test output through the TUI renderer
        if (renderer.isActive()) {
            runner.setOutputSink(renderer::printOutputLine);
        }

        // Build initial graph (may be empty if target/ dirs don't exist yet)
        DependencyGraph graph = buildGraph(classesDir, testClassesDir, patterns);

        // If initialRun=true, run the full suite before watching
        if (initialRun) {
            runner.runAll();
            int[] summary = runner.parseSurefireSummary();
            if (summary != null)
                renderer.updateSummary(summary);
            graph = buildGraph(classesDir, testClassesDir, patterns);
        }

        // CI escape hatch: exit after N seconds when testWatch.ciMaxRunSeconds is set
        String ciMax = System.getProperty("testWatch.ciMaxRunSeconds");
        if (ciMax != null) {
            int secs = Integer.parseInt(ciMax.trim());
            Thread exitThread = new Thread(() -> {
                try {
                    Thread.sleep(secs * 1000L);
                } catch (InterruptedException ignored) {
                }
                renderer.cleanup();
                System.exit(0);
            }, "ci-exit");
            exitThread.setDaemon(true);
            exitThread.start();
        }

        // Start file watcher
        List<Path> watchRoots = List.of(basedir.resolve("src"));
        FileWatcherService watcher = new FileWatcherService(
                watchRoots, includes, excludes, triggerQueue, debounceMillis);
        watcher.start();

        // Start TUI keyboard controller
        TuiController tui = new TuiController(triggerQueue, renderer);
        tui.start();

        // Event loop
        DependencyGraph currentGraph = graph;
        MavenTestRunner currentRunner = runner;

        while (true) {
            TriggerInfo trigger = triggerQueue.takeNext(); // blocks, marks RUNNING

            // Cancel any in-progress run
            currentRunner.cancel();

            FileChangeEvent event = trigger.getEvent();
            Set<String> toRun;
            if (event.getType() == WatchEventType.ALL || !smartSelection) {
                toRun = Collections.emptySet(); // empty = run all
            } else if (event.getType() == WatchEventType.FAILED) {
                toRun = currentRunner.getLastFailedFqns();
                if (toRun.isEmpty()) {
                    if (renderer.isActive()) {
                        renderer.printOutputLine(
                                YELLOW + "[test-watch] No failed tests recorded — running all." + RESET);
                    } else {
                        System.out.println(YELLOW + "[test-watch] No failed tests recorded — running all." + RESET);
                    }
                }
            } else {
                // CHANGED
                if (currentGraph.getSourceToTests().isEmpty() && currentGraph.getTestToSources().isEmpty()) {
                    if (renderer.isActive()) {
                        renderer.printOutputLine(YELLOW +
                                "[test-watch] No compiled classes found. " +
                                "Run 'mvn compile test-compile' first, or use 'test-watch:test'." + RESET);
                    } else {
                        System.out.println(YELLOW +
                                "[test-watch] No compiled classes found. " +
                                "Run 'mvn compile test-compile' first, or use 'test-watch:test'." + RESET);
                    }
                    triggerQueue.markDone(trigger.getId());
                    continue;
                }
                TestSelector.Result selection = TestSelector.select(
                        event.getChangedFiles(), currentGraph, sourceRoot, testSourceRoot, testPattern);
                if (selection.isAll()) {
                    toRun = Collections.emptySet();
                } else {
                    toRun = selection.getTestFqns();
                }
            }

            if (toRun.isEmpty()) {
                currentRunner.runAll();
            } else {
                currentRunner.run(toRun);
            }

            // Update summary from Surefire reports
            int[] summary = currentRunner.parseSurefireSummary();
            if (summary != null) {
                renderer.updateSummary(summary);
            }

            // Mark trigger done
            triggerQueue.markDone(trigger.getId());

            // Rebuild graph after each run
            currentGraph = buildGraph(classesDir, testClassesDir, patterns);
        }
    }

    private DependencyGraph buildGraph(Path classesDir, Path testClassesDir,
            List<String> patterns) {
        try {
            return DependencyGraph.build(classesDir, testClassesDir, patterns);
        } catch (IOException e) {
            LOG.warning("Failed to build dependency graph: " + e.getMessage() + " — using empty graph");
            return DependencyGraph.empty();
        }
    }
}
