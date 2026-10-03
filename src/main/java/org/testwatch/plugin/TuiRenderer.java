package org.testwatch.plugin;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedString;
import org.testwatch.plugin.model.TestRunResult;
import org.testwatch.plugin.model.TestRunResult.Status;
import org.testwatch.plugin.model.TriggerInfo;

/** A bounded split terminal, with a plain-output mode when no interactive terminal exists. */
public class TuiRenderer {
    private static final String RESET = "\033[0m";
    private Terminal terminal;
    private int width = 80;
    private int height = 24;
    private int panelHeight = 5;
    private boolean active;
    private TestRunResult lastResult;
    private TriggerQueue queue;

    public TuiRenderer() { }
    TuiRenderer(Terminal terminal) { this.terminal = terminal; }
    public synchronized void setTriggerQueue(TriggerQueue queue) { this.queue = queue; }

    public synchronized void setup() throws IOException {
        if (terminal == null) {
            try {
                terminal = TerminalBuilder.builder().system(true).dumb(false).build();
            } catch (IllegalStateException | IOException e) {
                terminal = null;
                return;
            }
        }
        if (terminal.getType().startsWith("dumb")) {
            terminal.close();
            terminal = null;
            return;
        }
        terminal.enterRawMode();
        active = true;
        terminal.handle(Terminal.Signal.WINCH, signal -> resize());
        updateSize();
        terminal.writer().print("\033[2J\033[H");
        applyScrollRegion();
        refreshBottomPanel();
    }

    public synchronized Terminal getTerminal() { return terminal; }
    public synchronized boolean isActive() { return active; }

    private synchronized void resize() {
        if (!active) return;
        updateSize();
        terminal.writer().print("\033[r\033[2J\033[H");
        applyScrollRegion();
        refreshBottomPanel();
    }

    public synchronized void printOutputLine(String line) {
        if (!active) {
            System.out.println(AttributedString.fromAnsi(line).toString());
            return;
        }
        PrintWriter writer = terminal.writer();
        writer.print("\0337\033[" + topEnd() + ";1H\n\033[" + topEnd() + ";1H\033[2K");
        writer.print(fit(line));
        writer.print("\0338");
        writer.flush();
    }

    public synchronized void updateResult(TestRunResult result) {
        lastResult = result;
        if (active) refreshBottomPanel();
        else if (result.getStatus() != Status.RUNNING) System.out.println(result.formatSummary());
    }

    /** Compatibility for callers supplying explicit counts. */
    public void updateSummary(int[] summary) {
        updateResult(new TestRunResult(summary[1] + summary[2] > 0 ? Status.FAILED : Status.PASSED,
                0, summary, java.util.Set.of(), null));
    }

    public synchronized void refreshBottomPanel() {
        if (!active) return;
        PrintWriter writer = terminal.writer();
        List<TriggerInfo> triggers = queue == null ? List.of() : queue.getVisibleTriggers();
        int previousTop = topEnd();
        panelHeight = Math.min(5 + Math.min(5, triggers.size()), Math.max(0, height - 1));
        int top = topEnd();
        if (previousTop != top) applyScrollRegion();
        writer.print("\0337");
        for (int row = Math.min(previousTop, top) + 1; row <= height; row++) clearRow(writer, row);
        // Always leave room for output; omit optional rows on very small terminals.
        int start = top + 1;
        int helpRow = panelHeight >= 3 ? height : -1;
        int summaryRow = helpRow > 0 ? Math.max(start, height - 2) : height;
        if (summaryRow > start) writeRow(writer, start, "\033[2m" + "─".repeat(width) + RESET);
        int row = start + 1;
        for (TriggerInfo trigger : triggers) {
            if (row >= summaryRow - 1) break;
            writeRow(writer, row++, "› Trigger " + trigger.getId() + ": " + trigger.getDescription()
                    + " (" + trigger.getStatus().name().toLowerCase() + ")");
        }
        writeRow(writer, summaryRow, summaryLine());
        if (helpRow > 0) {
            String help = "[test-watch] Watching for changes.  [r] rerun all  [f] rerun failed  [q] quit";
            if (help.length() > width) help = "[r] all  [f] failed  [q] quit";
            writeRow(writer, helpRow, "\033[36m" + help + RESET);
        }
        writer.print("\0338");
        writer.flush();
    }

    private String summaryLine() {
        if (lastResult == null) return "No test results yet.";
        Status status = lastResult.getStatus();
        String color = status == Status.PASSED ? "\033[32m"
                : status == Status.FAILED || status == Status.BUILD_ERROR ? "\033[31m" : "\033[33m";
        return color + "\033[1m" + lastResult.formatSummary() + RESET;
    }

    private void clearRow(PrintWriter writer, int row) {
        writer.print("\033[" + row + ";1H\033[2K");
    }

    private void writeRow(PrintWriter writer, int row, String text) {
        clearRow(writer, row);
        writer.print(fit(text));
    }

    private String fit(String text) {
        AttributedString value = AttributedString.fromAnsi(text.replace('\r', ' ').replace('\n', ' '));
        if (value.columnLength() > width) value = value.columnSubSequence(0, width);
        return value.toAnsi(terminal) + RESET;
    }

    private void updateSize() {
        width = Math.max(1, terminal.getWidth() > 0 ? terminal.getWidth() : 80);
        height = Math.max(1, terminal.getHeight() > 0 ? terminal.getHeight() : 24);
        panelHeight = Math.min(panelHeight, height - 1);
    }

    private int topEnd() { return Math.max(1, height - panelHeight); }

    private void applyScrollRegion() {
        terminal.writer().print("\033[1;" + topEnd() + "r\033[" + topEnd() + ";1H");
        terminal.writer().flush();
    }

    public synchronized void cleanup() {
        Terminal current = terminal;
        if (current == null) return;
        if (active) {
            PrintWriter writer = current.writer();
            writer.print(RESET + "\033[r");
            for (int row = topEnd() + 1; row <= height; row++) clearRow(writer, row);
            writer.print("\033[" + Math.min(height, topEnd() + 1) + ";1H");
            if (lastResult != null) writer.println(fit(lastResult.formatSummary()));
            writer.println("[test-watch] Stopped.");
            writer.flush();
        }
        active = false;
        terminal = null;
        try { current.close(); } catch (IOException ignored) { }
    }
}
