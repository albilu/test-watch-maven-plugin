package com.example.plugin;

import org.apache.maven.shared.invoker.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/**
 * Forks a Maven subprocess to run tests using maven-invoker.
 * Streams output to stdout with ANSI coloring.
 * Parses Surefire XML reports to track failed test FQNs.
 */
public class MavenTestRunner {

    private static final Logger LOG = Logger.getLogger(MavenTestRunner.class.getName());

    // ANSI codes
    private static final String RESET  = "\u001B[0m";
    private static final String GREEN  = "\u001B[32m";
    private static final String RED    = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN   = "\u001B[36m";

    private final File basedir;
    private final boolean parallel;
    private final File mavenHome;

    private volatile Process currentProcess;
    private volatile Set<String> lastFailedFqns = Collections.emptySet();

    public MavenTestRunner(File basedir, boolean parallel) {
        this.basedir = basedir;
        this.parallel = parallel;
        // Detect maven home from environment
        this.mavenHome = detectMavenHome();
    }

    /**
     * Run the full test suite (no -Dtest= filter).
     */
    public int runAll() {
        return invoke(Collections.emptySet());
    }

    /**
     * Run a specific set of test FQNs. If fqns is empty, runs all tests.
     */
    public int run(Set<String> testFqns) {
        return invoke(testFqns);
    }

    /** Returns the FQNs of tests that failed in the last run. */
    public Set<String> getLastFailedFqns() { return lastFailedFqns; }

    /** Kills the in-progress Maven subprocess if one is running. */
    public void cancel() {
        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            System.out.println(YELLOW + "\n[test-watch] Run cancelled." + RESET);
        }
    }

    // ---- private ----

    private int invoke(Set<String> testFqns) {
        Invoker invoker = new DefaultInvoker();
        if (mavenHome != null) invoker.setMavenHome(mavenHome);

        InvocationRequest request = new DefaultInvocationRequest();
        request.setBaseDirectory(basedir);
        request.setGoals(List.of("test"));
        request.setBatchMode(true);

        Properties props = new Properties();
        props.setProperty("maven.test.failure.ignore", "true");
        props.setProperty("surefire.failIfNoSpecifiedTests", "false");
        if (!testFqns.isEmpty()) {
            props.setProperty("test", String.join(",", testFqns));
        }
        if (parallel) {
            props.setProperty("parallel", "methods");
            props.setProperty("useUnlimitedThreads", "true");
        }
        request.setProperties(props);

        // Capture process for cancel-and-restart
        request.setOutputHandler(line -> {
            String colored = colorize(line);
            System.out.println(colored);
        });
        request.setErrorHandler(line -> System.err.println(YELLOW + line + RESET));

        System.out.println(CYAN + "\n[test-watch] Running: " +
            (testFqns.isEmpty() ? "all tests" : String.join(", ", testFqns)) + RESET);

        try {
            InvocationResult result = invoker.execute(request);
            // After run, parse surefire XML for failures
            lastFailedFqns = parseSurefireFailures();
            return result.getExitCode();
        } catch (MavenInvocationException e) {
            System.err.println(RED + "[test-watch] Maven invocation failed: " + e.getMessage() + RESET);
            return -1;
        }
    }

    private String colorize(String line) {
        if (line.contains("BUILD SUCCESS")) return GREEN + line + RESET;
        if (line.contains("BUILD FAILURE")) return RED + line + RESET;
        if (line.contains("COMPILATION ERROR") || line.contains("ERROR")) return YELLOW + line + RESET;
        if (line.contains("Tests run:") && line.contains("Failures: 0") && line.contains("Errors: 0"))
            return GREEN + line + RESET;
        if (line.contains("Tests run:") &&
            (line.contains("Failures:") || line.contains("Errors:")) &&
            !line.contains("Failures: 0, Errors: 0"))
            return RED + line + RESET;
        return line;
    }

    private Set<String> parseSurefireFailures() {
        Path reportsDir = basedir.toPath().resolve("target/surefire-reports");
        if (!Files.exists(reportsDir)) return Collections.emptySet();

        Set<String> failed = new LinkedHashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir, "TEST-*.xml")) {
            for (Path xml : stream) {
                String content = Files.readString(xml);
                // Simple string scan — good enough; full XML parse is over-engineering here
                if (content.contains("<failure") || content.contains("<error")) {
                    // Extract classname from <testsuite name="com.example.FooTest" ...>
                    int nameIdx = content.indexOf("classname=\"");
                    if (nameIdx < 0) nameIdx = content.indexOf("name=\"");
                    if (nameIdx >= 0) {
                        int start = content.indexOf('"', nameIdx) + 1;
                        int end = content.indexOf('"', start);
                        if (end > start) failed.add(content.substring(start, end));
                    }
                }
            }
        } catch (IOException e) {
            LOG.warning("Could not parse surefire reports: " + e.getMessage());
        }
        return failed;
    }

    private static File detectMavenHome() {
        String m2home = System.getenv("M2_HOME");
        if (m2home != null && !m2home.isBlank()) return new File(m2home);
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome != null && !mavenHome.isBlank()) return new File(mavenHome);
        // Try to find mvn on PATH
        try {
            ProcessBuilder pb = new ProcessBuilder("which", "mvn");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String line = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            if (line != null && !line.isBlank()) {
                return new File(line).getParentFile().getParentFile(); // bin/mvn -> ..
            }
        } catch (IOException ignored) {}
        return null;
    }
}
