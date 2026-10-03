package org.testwatch.plugin.model;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** The outcome and fresh test reports from one Maven invocation. */
public final class TestRunResult {
    public enum Status { RUNNING, PASSED, FAILED, BUILD_ERROR, CANCELLED, NO_TESTS }

    private final Status status;
    private final int exitCode;
    private final int[] summary;
    private final Set<String> failedTests;
    private final String message;

    public TestRunResult(Status status, int exitCode, int[] summary, Set<String> failedTests, String message) {
        this.status = status;
        this.exitCode = exitCode;
        this.summary = summary == null ? null : summary.clone();
        this.failedTests = Collections.unmodifiableSet(new LinkedHashSet<>(failedTests));
        this.message = message;
    }

    public static TestRunResult running() {
        return new TestRunResult(Status.RUNNING, 0, null, Set.of(), "Running tests...");
    }

    public Status getStatus() { return status; }
    public int getExitCode() { return exitCode; }
    public int[] getSummary() { return summary == null ? null : summary.clone(); }
    public Set<String> getFailedTests() { return failedTests; }
    public String getMessage() { return message; }

    public String formatSummary() {
        String label;
        switch (status) {
            case PASSED: label = "PASS"; break;
            case FAILED: label = "FAIL"; break;
            case BUILD_ERROR: label = "BUILD ERROR"; break;
            case CANCELLED: label = "CANCELLED"; break;
            case NO_TESTS: label = "NO TESTS"; break;
            default: label = "RUNNING";
        }
        if (summary == null || status == Status.CANCELLED || status == Status.RUNNING) {
            return label + (message == null || message.isBlank() ? "" : "  " + message);
        }
        StringBuilder text = new StringBuilder(label).append("  Tests: ");
        if (summary[1] > 0) text.append(summary[1]).append(" failed, ");
        if (summary[2] > 0) text.append(summary[2]).append(" errors, ");
        if (summary[3] > 0) text.append(summary[3]).append(" skipped, ");
        text.append(summary[0] - summary[1] - summary[2] - summary[3]).append(" passed");
        if (message != null && !message.isBlank()) text.append(" — ").append(message);
        return text.toString();
    }
}
