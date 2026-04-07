package com.example.plugin;

import com.example.plugin.model.TriggerInfo;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import java.util.logging.Logger;

/**
 * JLine-based split-panel terminal renderer.
 *
 * Layout:
 * 
 * <pre>
 *   Lines 1..topEnd      — scrolling test output (ANSI scroll region)
 *   Lines topEnd+1..H    — fixed bottom panel (separator, queue, summary, help)
 * </pre>
 *
 * Uses ANSI scroll regions ({@code \033[top;bottom r}) so that output printed
 * in
 * the top area scrolls naturally without overwriting the bottom panel.
 */
public class TuiRenderer {

    private static final Logger LOG = Logger.getLogger(TuiRenderer.class.getName());

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";
    private static final String DIM = "\u001B[2m";

    /** Minimum lines reserved for output scroll area. */
    private static final int MIN_TOP_HEIGHT = 6;
    /**
     * Base bottom panel lines: separator(1) + blank(1) + summary(1) + blank(1) +
     * help(1) = 5
     */
    private static final int BASE_BOTTOM_LINES = 5;
    /** Max trigger lines shown in the panel. */
    private static final int MAX_TRIGGER_LINES = 5;

    private Terminal terminal;
    private int width;
    private int height;
    private int bottomPanelHeight = BASE_BOTTOM_LINES;
    private boolean active;

    /** Latest summary from Surefire (total, failures, errors, skipped), or null. */
    private volatile int[] lastSummary;

    /** Reference to trigger queue for panel rendering. */
    private volatile TriggerQueue triggerQueue;

    public void setTriggerQueue(TriggerQueue triggerQueue) {
        this.triggerQueue = triggerQueue;
    }

    /**
     * Initialize the JLine terminal, enter raw mode, and set up the scroll region.
     */
    public void setup() throws IOException {
        terminal = TerminalBuilder.builder()
                .system(true)
                .jansi(true)
                .build();
        terminal.enterRawMode();

        // Handle resize
        terminal.handle(Terminal.Signal.WINCH, sig -> {
            updateSize();
            applyScrollRegion();
            refreshBottomPanel();
        });

        updateSize();

        // Clear screen and set up initial layout
        PrintWriter w = terminal.writer();
        w.print("\033[2J"); // clear screen
        w.print("\033[H"); // cursor home
        w.flush();

        applyScrollRegion();
        refreshBottomPanel();
        active = true;
    }

    /**
     * Returns the JLine Terminal so TuiController can read keys from it.
     */
    public Terminal getTerminal() {
        return terminal;
    }

    /**
     * Print a line of test output in the scrolling top region.
     * Thread-safe.
     */
    public synchronized void printOutputLine(String line) {
        if (terminal == null)
            return;
        PrintWriter w = terminal.writer();

        int topEnd = getTopEnd();

        // Save cursor, move to bottom of scroll region, print, restore cursor
        w.print("\0337"); // save cursor (DEC)
        w.print("\033[" + topEnd + ";1H"); // move to last line of scroll region
        w.print("\n"); // trigger scroll within region
        w.print("\033[" + topEnd + ";1H"); // reposition at last line
        w.print("\033[2K"); // clear line
        // Truncate line to terminal width to avoid wrapping into bottom panel
        String truncated = truncate(
                stripAnsi(line).length() > width ? line.substring(0, Math.min(line.length(), width)) : line, width);
        w.print(truncated);
        w.print("\0338"); // restore cursor (DEC)
        w.flush();
    }

    /**
     * Update the test result summary (from Surefire).
     */
    public void updateSummary(int[] summary) {
        this.lastSummary = summary;
        refreshBottomPanel();
    }

    /**
     * Refresh the bottom panel with current trigger queue and summary.
     * Thread-safe.
     */
    public synchronized void refreshBottomPanel() {
        if (terminal == null)
            return;
        PrintWriter w = terminal.writer();

        List<TriggerInfo> triggers = triggerQueue != null ? triggerQueue.getVisibleTriggers() : List.of();

        // Recalculate bottom panel height
        int triggerLines = Math.min(triggers.size(), MAX_TRIGGER_LINES);
        int newHeight = BASE_BOTTOM_LINES + triggerLines;
        if (newHeight != bottomPanelHeight) {
            bottomPanelHeight = newHeight;
            applyScrollRegion();
        }

        int topEnd = getTopEnd();
        int panelStart = topEnd + 1;

        // Save cursor, move below scroll region
        w.print("\0337");

        // Draw separator line
        w.print("\033[" + panelStart + ";1H");
        w.print("\033[2K");
        w.print(DIM);
        w.print(repeat("─", width));
        w.print(RESET);

        // Draw trigger lines (most recent at bottom, queued above running)
        int row = panelStart + 1;
        for (int i = triggers.size() - 1; i >= 0 && row < panelStart + 1 + MAX_TRIGGER_LINES; i--, row++) {
            TriggerInfo t = triggers.get(i);
            w.print("\033[" + row + ";1H");
            w.print("\033[2K");
            String statusLabel = t.getStatus().name().toLowerCase();
            String color = t.getStatus() == com.example.plugin.model.TriggerStatus.RUNNING ? YELLOW : DIM;
            w.print(color + "› Trigger " + t.getId() + ": " + truncate(t.getDescription(), width - 30) + " ("
                    + statusLabel + ")" + RESET);
        }

        // Blank line
        w.print("\033[" + (panelStart + 1 + triggerLines) + ";1H");
        w.print("\033[2K");

        // Summary line
        int summaryRow = panelStart + 2 + triggerLines;
        w.print("\033[" + summaryRow + ";1H");
        w.print("\033[2K");
        w.print(buildSummaryLine());

        // Blank line
        w.print("\033[" + (summaryRow + 1) + ";1H");
        w.print("\033[2K");

        // Help line
        int helpRow = summaryRow + 2;
        w.print("\033[" + helpRow + ";1H");
        w.print("\033[2K");
        w.print(CYAN + "[test-watch] Watching for changes." +
                "  [r] rerun all  [f] rerun failed  [q] quit" + RESET);

        // Restore cursor
        w.print("\0338");
        w.flush();
    }

    /**
     * Clean up: reset scroll region, clear screen, close terminal.
     */
    public synchronized void cleanup() {
        if (terminal == null)
            return;
        active = false;
        try {
            PrintWriter w = terminal.writer();
            w.print("\033[r"); // reset scroll region
            w.print("\033[2J"); // clear screen
            w.print("\033[H"); // cursor home
            w.flush();
            terminal.close();
        } catch (IOException e) {
            LOG.fine("Error closing terminal: " + e.getMessage());
        }
        terminal = null;
    }

    public boolean isActive() {
        return active;
    }

    // ---- internal ----

    private void updateSize() {
        if (terminal != null) {
            width = terminal.getWidth();
            height = terminal.getHeight();
            if (width <= 0)
                width = 80;
            if (height <= 0)
                height = 24;
        }
    }

    private int getTopEnd() {
        int topEnd = height - bottomPanelHeight;
        return Math.max(topEnd, MIN_TOP_HEIGHT);
    }

    private void applyScrollRegion() {
        if (terminal == null)
            return;
        int topEnd = getTopEnd();
        PrintWriter w = terminal.writer();
        w.print("\033[1;" + topEnd + "r"); // set scroll region
        w.print("\033[" + topEnd + ";1H"); // put cursor at end of scroll region
        w.flush();
    }

    private String buildSummaryLine() {
        int[] summary = lastSummary;
        if (summary == null) {
            return DIM + "No test results yet." + RESET;
        }

        int total = summary[0], failures = summary[1], errors = summary[2], skipped = summary[3];
        int passed = total - failures - errors - skipped;
        boolean hasFail = (failures + errors) > 0;

        StringBuilder sb = new StringBuilder();
        if (hasFail) {
            sb.append(RED).append(BOLD).append("FAIL").append(RESET);
        } else {
            sb.append(GREEN).append(BOLD).append("PASS").append(RESET);
        }
        sb.append("  Tests: ");
        if (failures > 0) {
            sb.append(RED).append(failures).append(" failed").append(RESET).append(", ");
        }
        if (errors > 0) {
            sb.append(RED).append(errors).append(" errors").append(RESET).append(", ");
        }
        if (skipped > 0) {
            sb.append(YELLOW).append(skipped).append(" skipped").append(RESET).append(", ");
        }
        sb.append(GREEN).append(passed).append(" passed").append(RESET);

        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (maxLen <= 0)
            return s;
        String plain = stripAnsi(s);
        if (plain.length() <= maxLen)
            return s;
        // Rough truncation — may cut in the middle of an ANSI sequence in edge cases
        return s.substring(0, Math.min(s.length(), maxLen - 3)) + "...";
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[;\\d]*[A-Za-z]", "");
    }

    private static String repeat(String s, int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++)
            sb.append(s);
        return sb.toString();
    }
}
