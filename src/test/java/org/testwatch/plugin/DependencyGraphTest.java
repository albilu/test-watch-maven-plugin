package org.testwatch.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DependencyGraphTest {

    @TempDir
    Path tempDir;

    /**
     * Copies the compiled .class files for the plugin's own test fixtures into
     * simulated target/classes and target/test-classes directories, then asserts
     * that DependencyGraph correctly maps FooTest -> Foo and NOT FooTest -> Bar.
     *
     * We use the plugin project's own compiled test-fixture classes (compiled by
     * Maven during test-compile phase) as the .class inputs. Fixtures live in
     * src/test/java/org/testwatch/fixture/.
     */
    @Test
    void testForwardAndReverseMap(@TempDir Path classesDir, @TempDir Path testClassesDir) throws IOException {
        // Copy fixture classes into temp dirs (see Task 3, Step 2 for fixture creation)
        copyFixtureClass("org/testwatch/fixture/Foo.class", classesDir);
        copyFixtureClass("org/testwatch/fixture/Bar.class", classesDir);
        copyFixtureClass("org/testwatch/fixture/FooTest.class", testClassesDir);

        DependencyGraph graph = DependencyGraph.build(
                classesDir, testClassesDir,
                List.of("**/*Test.java", "**/*Tests.java"));

        // FooTest references Foo — should be in graph
        Set<String> sourcesForFooTest = graph.getTestToSources().get("org.testwatch.fixture.FooTest");
        assertNotNull(sourcesForFooTest, "FooTest should be in graph");
        assertTrue(sourcesForFooTest.contains("org.testwatch.fixture.Foo"), "FooTest should reference Foo");
        assertFalse(sourcesForFooTest.contains("org.testwatch.fixture.Bar"), "FooTest should NOT reference Bar");

        // Reverse map: Foo -> FooTest
        Set<String> testsForFoo = graph.getSourceToTests().get("org.testwatch.fixture.Foo");
        assertNotNull(testsForFoo);
        assertTrue(testsForFoo.contains("org.testwatch.fixture.FooTest"));

        // Reverse map: Bar -> no tests (nothing references Bar)
        Set<String> testsForBar = graph.getSourceToTests().get("org.testwatch.fixture.Bar");
        assertTrue(testsForBar == null || testsForBar.isEmpty());
    }

    private void copyFixtureClass(String relativePath, Path targetDir) throws IOException {
        Path dest = targetDir.resolve(relativePath);
        Files.createDirectories(dest.getParent());
        // The fixture .class files are compiled alongside tests; locate via classloader
        try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream(relativePath)) {
            assertNotNull(in, "Fixture class not found on classpath: " + relativePath);
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
