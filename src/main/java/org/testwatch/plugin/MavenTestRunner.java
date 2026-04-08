package org.testwatch.plugin;

import org.apache.maven.shared.invoker.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Forks a Maven subprocess to run tests using maven-invoker.
 * Streams output to stdout with ANSI coloring.
 * Parses Surefire XML reports to track failed test FQNs.
 */
public class MavenTestRunner {

    private static final Logger LOG = Logger.getLogger(MavenTestRunner.class.getName());

    // ANSI codes
    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";

    private final File basedir;
    private final boolean parallel;
    private final File mavenHome;
    private Consumer<String> outputSink;

    private volatile Process currentProcess;
    private volatile Set<String> lastFailedFqns = Collections.emptySet();

    public MavenTestRunner(File basedir, boolean parallel) {
        this.basedir = basedir;
        this.parallel = parallel;
        this.outputSink = System.out::println; // default
        this.mavenHome = detectMavenHome();
    }

    /**
     * Set the output sink for test output lines. Defaults to System.out::println.
     */
    public void setOutputSink(Consumer<String> outputSink) {
        this.outputSink = outputSink;
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
    public Set<String> getLastFailedFqns() {
        return lastFailedFqns;
    }

    /** Kills the in-progress Maven subprocess if one is running. */
    public void cancel() {
        Process p = currentProcess;
        if (p != null && p.isAlive()) {
            p.destroyForcibly();
            outputSink.accept(YELLOW + "[test-watch] Run cancelled." + RESET);
        }
    }

    // ---- private ----

    private int invoke(Set<String> testFqns) {
        Invoker invoker = new DefaultInvoker();
        if (mavenHome != null)
            invoker.setMavenHome(mavenHome);

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
        Consumer<String> sink = this.outputSink;
        request.setOutputHandler(line -> {
            String colored = colorize(line);
            if (colored != null) {
                sink.accept(colored);
            }
        });
        request.setErrorHandler(line -> sink.accept(YELLOW + line + RESET));

        outputSink.accept(CYAN + "[test-watch] Running: " +
                (testFqns.isEmpty() ? "all tests" : String.join(", ", testFqns)) + RESET);

        try {
            InvocationResult result = invoker.execute(request);
            // After run, parse surefire XML for failures
            lastFailedFqns = parseSurefireFailures();
            printSummary();
            return result.getExitCode();
        } catch (MavenInvocationException e) {
            outputSink.accept(RED + "[test-watch] Maven invocation failed: " + e.getMessage() + RESET);
            return -1;
        }
    }

    private String colorize(String line) {
        // Suppress JaCoCo execution-data mismatch warnings (noise in watch mode)
        if (line.contains("[WARNING]") && line.contains("Execution data for class")
                && line.contains("does not match")) {
            return null; // null signals "suppress this line"
        }
        if (line.contains("BUILD SUCCESS"))
            return GREEN + line + RESET;
        if (line.contains("BUILD FAILURE"))
            return RED + line + RESET;
        if (line.contains("COMPILATION ERROR") || line.contains("[ERROR]"))
            return YELLOW + line + RESET;
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
        if (!Files.exists(reportsDir))
            return Collections.emptySet();

        Set<String> failed = new LinkedHashSet<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir, "TEST-*.xml")) {
            for (Path xml : stream) {
                String content = Files.readString(xml);
                // Simple string scan — good enough; full XML parse is over-engineering here
                if (content.contains("<failure") || content.contains("<error")) {
                    // Extract classname from <testsuite name="org.testwatch.FooTest" ...>
                    int nameIdx = content.indexOf("classname=\"");
                    if (nameIdx < 0)
                        nameIdx = content.indexOf("name=\"");
                    if (nameIdx >= 0) {
                        int start = content.indexOf('"', nameIdx) + 1;
                        int end = content.indexOf('"', start);
                        if (end > start)
                            failed.add(content.substring(start, end));
                    }
                }
            }
        } catch (IOException e) {
            LOG.warning("Could not parse surefire reports: " + e.getMessage());
        }
        return failed;
    }

    /**
     * Parses all Surefire XML reports and returns [total, failures, errors,
     * skipped].
     * Returns null if no reports are found.
     */
    int[] parseSurefireSummary() {
        Path reportsDir = basedir.toPath().resolve("target/surefire-reports");
        if (!Files.exists(reportsDir))
            return null;

        int total = 0, failures = 0, errors = 0, skipped = 0;
        boolean found = false;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(reportsDir, "TEST-*.xml")) {
            for (Path xml : stream) {
                found = true;
                String content = Files.readString(xml);
                // Extract attributes from <testsuite tests="N" failures="N" errors="N"
                // skipped="N">
                total += extractIntAttr(content, "tests");
                failures += extractIntAttr(content, "failures");
                errors += extractIntAttr(content, "errors");
                skipped += extractIntAttr(content, "skipped");
            }
        } catch (IOException e) {
            LOG.warning("Could not parse surefire reports for summary: " + e.getMessage());
            return null;
        }

        return found ? new int[] { total, failures, errors, skipped } : null;
    }

    private int extractIntAttr(String xml, String attrName) {
        String search = attrName + "=\"";
        int idx = xml.indexOf(search);
        if (idx < 0)
            return 0;
        int start = idx + search.length();
        int end = xml.indexOf('"', start);
        if (end < 0)
            return 0;
        try {
            return Integer.parseInt(xml.substring(start, end).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void printSummary() {
        int[] summary = parseSurefireSummary();
        if (summary == null)
            return;

        int total = summary[0], failures = summary[1], errors = summary[2], skipped = summary[3];
        int passed = total - failures - errors - skipped;
        boolean hasFail = (failures + errors) > 0;

        StringBuilder sb = new StringBuilder();
        if (hasFail) {
            sb.append(RED).append(" FAIL ").append(RESET);
        } else {
            sb.append(GREEN).append(" PASS ").append(RESET);
        }
        sb.append(" Tests: ");
        if (hasFail) {
            sb.append(RED).append(failures + errors).append(" failed").append(RESET).append(", ");
        }
        if (skipped > 0) {
            sb.append(YELLOW).append(skipped).append(" skipped").append(RESET).append(", ");
        }
        sb.append(GREEN).append(passed).append(" passed").append(RESET);

        outputSink.accept(sb.toString());
    }

    private static File detectMavenHome() {
        String m2home = System.getenv("M2_HOME");
        if (m2home != null && !m2home.isBlank())
            return new File(m2home);
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome != null && !mavenHome.isBlank())
            return new File(mavenHome);
        // Try to find mvn on PATH
        try {
            ProcessBuilder pb = new ProcessBuilder("which", "mvn");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String line = new BufferedReader(new InputStreamReader(p.getInputStream())).readLine();
            if (line != null && !line.isBlank()) {
                return new File(line).getParentFile().getParentFile(); // bin/mvn -> ..
            }
        } catch (IOException ignored) {
        }
        return null;
    }
}
