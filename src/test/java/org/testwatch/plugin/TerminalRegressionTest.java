package org.testwatch.plugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.regex.Pattern;
import org.jline.terminal.Size;
import org.jline.terminal.impl.DumbTerminal;
import org.junit.jupiter.api.Test;
import org.testwatch.plugin.model.TestRunResult;
import org.testwatch.plugin.model.TestRunResult.Status;
import static org.junit.jupiter.api.Assertions.*;

class TerminalRegressionTest {
    @Test void panelNeverAddressesRowsOutsideSmallTerminalsAndAlwaysResetsOnClose() throws Exception {
        for (int height : new int[]{1, 2, 5, 15}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            DumbTerminal terminal = new DumbTerminal("test", "xterm", new ByteArrayInputStream(new byte[0]), output, StandardCharsets.UTF_8);
            terminal.setSize(new Size(25, height));
            TuiRenderer renderer = new TuiRenderer(terminal);
            renderer.setup();
            renderer.updateResult(new TestRunResult(Status.BUILD_ERROR, 1, null, Set.of(), "Compilation failed"));
            renderer.cleanup();
            String text = output.toString(StandardCharsets.UTF_8);
            var positions = Pattern.compile("\033\\[(\\d+);\\d+H").matcher(text);
            while (positions.find()) assertTrue(Integer.parseInt(positions.group(1)) <= height, text);
            assertTrue(text.contains("\033[r"));
            assertFalse(renderer.isActive());
        }
    }

    @Test void keyboardStopsAtEndOfInput() throws Exception {
        DumbTerminal terminal = new DumbTerminal("test", "xterm", new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), StandardCharsets.UTF_8);
        terminal.setSize(new Size(80, 24));
        TuiRenderer renderer = new TuiRenderer(terminal);
        try {
            renderer.setup();
            TuiController controller = new TuiController(new TriggerQueue(), renderer, () -> fail("EOF is not a quit command"));
            controller.start();
            controller.join(1000);
            assertFalse(controller.isAlive());
        } finally {
            renderer.cleanup();
        }
    }
}
