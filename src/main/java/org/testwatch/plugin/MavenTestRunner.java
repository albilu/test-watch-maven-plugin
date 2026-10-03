package org.testwatch.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.MavenCommandLineBuilder;
import org.testwatch.plugin.model.TestRunResult;
import org.testwatch.plugin.model.TestRunResult.Status;

/** Runs a cancellable Maven child with the originating session's configuration. */
public class MavenTestRunner {
    private final File basedir;
    private final File pom;
    private final boolean parallel;
    private final MavenSession session;
    private final Set<Path> reportDirectories;
    private final Object processLock = new Object();
    private Consumer<String> outputSink = System.out::println;
    private boolean summaryEnabled = true;
    private boolean interactiveOutput;
    private volatile Process currentProcess;
    private volatile boolean invoking;
    private volatile boolean cancelled;
    private volatile TestRunResult lastResult;
    private volatile TestRunResult lastCompletedResult;

    public MavenTestRunner(File basedir, boolean parallel) {
        this.basedir = basedir;
        this.pom = new File(basedir, "pom.xml");
        this.parallel = parallel;
        this.session = null;
        this.reportDirectories = Set.of(basedir.toPath().resolve("target/surefire-reports"));
    }

    MavenTestRunner(MavenProject project, MavenSession session, List<ProjectLayout> layouts, boolean parallel) {
        this.session = session;
        this.pom = session != null && session.getRequest().getPom() != null
                ? session.getRequest().getPom() : project.getFile();
        this.basedir = pom.getAbsoluteFile().getParentFile();
        this.parallel = parallel;
        this.reportDirectories = layouts.stream().flatMap(p -> p.reports.stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public void setOutputSink(Consumer<String> sink) { outputSink = sink; }
    public void setSummaryEnabled(boolean enabled) { summaryEnabled = enabled; }
    public void setInteractiveOutput(boolean interactive) { interactiveOutput = interactive; }
    public int runAll() { return invoke(Collections.emptySet()); }
    public int run(Set<String> tests) { return invoke(tests); }
    public TestRunResult getLastResult() { return lastResult; }
    public Set<String> getLastFailedFqns() {
        TestRunResult completed = lastCompletedResult;
        return completed == null ? Set.of() : completed.getFailedTests();
    }

    /** Returns the current invocation's counts, never historical reports. */
    int[] parseSurefireSummary() { return lastResult == null ? null : lastResult.getSummary(); }

    /** Stops this runner's child and its descendants, including forked test JVMs. */
    public void cancel() {
        Process process;
        synchronized (processLock) {
            if (!invoking) return;
            cancelled = true;
            process = currentProcess;
        }
        if (process != null) terminate(process);
    }

    private static void terminate(Process process) {
        List<ProcessHandle> children = process.descendants().collect(Collectors.toList());
        Collections.reverse(children);
        children.forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            process.waitFor(300, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    InvocationRequest createRequest(Set<String> tests) {
        InvocationRequest request = new DefaultInvocationRequest();
        request.setBaseDirectory(basedir).setPomFile(pom).setGoals(List.of("test")).setBatchMode(true);
        Properties properties = new Properties();
        if (session != null) {
            MavenExecutionRequest parent = session.getRequest();
            properties.putAll(session.getUserProperties());
            request.setOffline(parent.isOffline()).setUpdateSnapshots(parent.isUpdateSnapshots())
                    .setRecursive(parent.isRecursive()).setShowErrors(parent.isShowErrors())
                    .setUserSettingsFile(existing(parent.getUserSettingsFile())).setGlobalSettingsFile(existing(parent.getGlobalSettingsFile()))
                    .setToolchainsFile(existing(parent.getUserToolchainsFile())).setGlobalToolchainsFile(existing(parent.getGlobalToolchainsFile()))
                    .setLocalRepositoryDirectory(parent.getLocalRepositoryPath()).setResumeFrom(parent.getResumeFrom());
            List<String> profiles = new ArrayList<>(parent.getActiveProfiles());
            parent.getInactiveProfiles().forEach(p -> profiles.add("!" + p));
            request.setProfiles(profiles);
            List<String> projects = new ArrayList<>(parent.getSelectedProjects());
            parent.getExcludedProjects().forEach(p -> projects.add("!" + p));
            request.setProjects(projects);
            String make = parent.getMakeBehavior();
            request.setAlsoMake(MavenExecutionRequest.REACTOR_MAKE_UPSTREAM.equals(make)
                    || MavenExecutionRequest.REACTOR_MAKE_BOTH.equals(make));
            request.setAlsoMakeDependents(MavenExecutionRequest.REACTOR_MAKE_DOWNSTREAM.equals(make)
                    || MavenExecutionRequest.REACTOR_MAKE_BOTH.equals(make));
            if (parent.getDegreeOfConcurrency() > 1) request.setThreads(String.valueOf(parent.getDegreeOfConcurrency()));
            if (parent.isNoSnapshotUpdates()) request.addArg("-nsu");
            if (MavenExecutionRequest.CHECKSUM_POLICY_FAIL.equals(parent.getGlobalChecksumPolicy())) request.addArg("-C");
            if (MavenExecutionRequest.CHECKSUM_POLICY_WARN.equals(parent.getGlobalChecksumPolicy())) request.addArg("-c");
            if (MavenExecutionRequest.REACTOR_FAIL_AT_END.equals(parent.getReactorFailureBehavior())) request.addArg("-fae");
            if (MavenExecutionRequest.REACTOR_FAIL_NEVER.equals(parent.getReactorFailureBehavior())) request.addArg("-fn");
        }
        // The child writes to pipes, so Maven cannot detect the outer terminal itself.
        // Translate automatic color detection while preserving an explicit color preference.
        String defaultColor = session == null ? System.getProperty("style.color", "auto")
                : session.getSystemProperties().getProperty("style.color", "auto");
        String color = properties.getProperty("style.color", defaultColor);
        properties.setProperty("style.color", interactiveOutput && "auto".equals(color) ? "always" : color);
        properties.putIfAbsent("maven.test.failure.ignore", "true");
        // Other reactor modules may legitimately contain none of the selected classes.
        properties.setProperty("surefire.failIfNoSpecifiedTests", "false");
        if (!tests.isEmpty()) properties.setProperty("test", String.join(",", tests));
        if (parallel) {
            properties.putIfAbsent("parallel", "methods");
            properties.putIfAbsent("useUnlimitedThreads", "true");
            properties.putIfAbsent("junit.jupiter.execution.parallel.enabled", "true");
            properties.putIfAbsent("junit.jupiter.execution.parallel.mode.default", "concurrent");
            properties.putIfAbsent("junit.jupiter.execution.parallel.mode.classes.default", "same_thread");
        }
        request.setProperties(properties);
        File mavenHome = detectMavenHome();
        if (mavenHome != null) request.setMavenHome(mavenHome);
        return request;
    }

    private static File existing(File file) {
        // Maven includes optional default paths in its request even when no file exists.
        return file != null && file.isFile() ? file : null;
    }

    private int invoke(Set<String> tests) {
        synchronized (processLock) {
            if (invoking) throw new IllegalStateException("A Maven invocation is already running");
            invoking = true;
            cancelled = false;
        }
        Process process = null;
        TestRunResult result;
        try {
            Map<Path, SurefireReports.Stamp> before = SurefireReports.snapshot(reportDirectories);
            var command = new MavenCommandLineBuilder().build(createRequest(tests));
            outputSink.accept("[test-watch] Running: " + (tests.isEmpty() ? "all tests" : String.join(", ", tests)));
            synchronized (processLock) {
                if (!cancelled) {
                    process = command.execute();
                    currentProcess = process;
                }
            }
            if (process == null) {
                result = cancelledResult();
            } else {
                process.getOutputStream().close();
                Thread stdout = stream(process.getInputStream(), "maven-output");
                Thread stderr = stream(process.getErrorStream(), "maven-errors");
                int exit = process.waitFor();
                stdout.join(2000);
                stderr.join(2000);
                if (cancelled) {
                    result = cancelledResult();
                } else {
                    SurefireReports.Results reports = SurefireReports.readFresh(reportDirectories, before);
                    int[] counts = reports.found ? reports.counts : null;
                    Status status = exit != 0 ? Status.BUILD_ERROR
                            : !reports.found || reports.counts[0] == 0 ? Status.NO_TESTS
                            : reports.counts[1] + reports.counts[2] > 0 ? Status.FAILED : Status.PASSED;
                    String message = exit != 0 ? "Maven exited with code " + exit : null;
                    if (status == Status.NO_TESTS) {
                        message = tests.isEmpty() ? "No tests executed in this run." : "No tests matched the selection.";
                    }
                    result = new TestRunResult(status, exit, counts, reports.failed, message);
                }
            }
        } catch (InterruptedException e) {
            cancel();
            Thread.currentThread().interrupt();
            result = cancelledResult();
        } catch (Exception e) {
            result = cancelled ? cancelledResult()
                    : new TestRunResult(Status.BUILD_ERROR, -1, null, Set.of(), e.getMessage());
        } finally {
            if (process != null) {
                if (process.isAlive()) terminate(process);
                try { process.getInputStream().close(); } catch (Exception ignored) { }
                try { process.getErrorStream().close(); } catch (Exception ignored) { }
            }
            synchronized (processLock) {
                currentProcess = null;
                invoking = false;
            }
        }
        lastResult = result;
        if (result.getStatus() != Status.CANCELLED) lastCompletedResult = result;
        if (summaryEnabled) outputSink.accept(result.formatSummary());
        return result.getExitCode();
    }

    private TestRunResult cancelledResult() {
        return new TestRunResult(Status.CANCELLED, -1, null, Set.of(), "Run stopped.");
    }

    private Thread stream(InputStream input, String name) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, Charset.defaultCharset()))) {
                String line;
                while ((line = reader.readLine()) != null) outputSink.accept(line);
            } catch (Exception e) {
                if (!cancelled) outputSink.accept("[test-watch] Output stream closed: " + e.getMessage());
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static File detectMavenHome() {
        String current = System.getProperty("maven.home");
        if (current != null && !current.isBlank()) return new File(current);
        for (String variable : List.of("MAVEN_HOME", "M2_HOME")) {
            String value = System.getenv(variable);
            if (value != null && !value.isBlank()) return new File(value);
        }
        String searchPath = System.getenv("PATH");
        if (searchPath != null) {
            String executable = System.getProperty("os.name").startsWith("Windows") ? "mvn.cmd" : "mvn";
            for (String directory : searchPath.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                Path candidate = Path.of(directory).resolve(executable);
                if (Files.isExecutable(candidate)) {
                    try { return candidate.toRealPath().getParent().getParent().toFile(); }
                    catch (Exception ignored) { }
                }
            }
        }
        return null;
    }
}
