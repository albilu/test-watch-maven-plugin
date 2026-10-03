package org.testwatch.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual Maven output over the runner's pipes, without resolving project dependencies. */
@Timeout(30)
class MavenOutputColorTest {
    @TempDir Path base;

    @Test void interactiveMavenOutputContainsNativeColors() throws Exception {
        assertTrue(buildSuccess(true, null).contains("\033["));
    }

    @Test void explicitNeverKeepsMavenOutputUncoloredInATerminal() throws Exception {
        assertFalse(buildSuccess(true, "never").contains("\033["));
    }

    @Test void automaticColorUsesTheOuterTerminalInsteadOfTheChildPipe() throws Exception {
        assertTrue(buildSuccess(true, "auto").contains("\033["));
    }

    @Test void redirectedOutputDoesNotForceColors() throws Exception {
        assertFalse(buildSuccess(false, null).contains("\033["));
    }

    @Test void parentJvmColorPreferencesAreForwardedToTheChild() throws Exception {
        assertFalse(buildSuccess(true, null, "never").contains("\033["));
        assertTrue(buildSuccess(false, null, "always").contains("\033["));
    }

    private String buildSuccess(boolean interactive, String color) throws Exception {
        return buildSuccess(interactive, color, null);
    }

    private String buildSuccess(boolean interactive, String color, String systemColor) throws Exception {
        Path pom = base.resolve("pom.xml");
        Files.writeString(pom, "<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId>"
                + "<artifactId>colors</artifactId><version>1</version><packaging>pom</packaging></project>");
        Properties properties = new Properties();
        if (color != null) properties.setProperty("style.color", color);
        Properties systemProperties = new Properties();
        if (systemColor != null) systemProperties.setProperty("style.color", systemColor);
        var request = new DefaultMavenExecutionRequest();
        request.setPom(pom.toFile()).setUserProperties(properties).setSystemProperties(systemProperties).setOffline(true);
        MavenSession session = new MavenSession(null, null, request, new DefaultMavenExecutionResult());
        MavenProject project = new MavenProject();
        project.setFile(pom.toFile());
        MavenTestRunner runner = new MavenTestRunner(project, session, List.of(), false);
        List<String> output = new CopyOnWriteArrayList<>();
        runner.setOutputSink(output::add);
        runner.setInteractiveOutput(interactive);
        try {
            assertEquals(0, runner.runAll(), () -> String.join("\n", output));
            return output.stream().filter(line -> line.contains("BUILD SUCCESS")).findFirst()
                    .orElseThrow(() -> new AssertionError(String.join("\n", output)));
        } finally {
            runner.cancel();
        }
    }
}
