package com.example.plugin;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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
     * src/test/java/com/example/fixture/.
     */
    @Test
    void testForwardAndReverseMap(@TempDir Path classesDir, @TempDir Path testClassesDir) throws IOException {
        // Copy fixture classes into temp dirs (see Task 3, Step 2 for fixture creation)
        copyFixtureClass("com/example/fixture/Foo.class", classesDir);
        copyFixtureClass("com/example/fixture/Bar.class", classesDir);
        copyFixtureClass("com/example/fixture/FooTest.class", testClassesDir);

        DependencyGraph graph = DependencyGraph.build(
            classesDir, testClassesDir,
            List.of("**/*Test.java", "**/*Tests.java")
        );

        // FooTest references Foo — should be in graph
        Set<String> sourcesForFooTest = graph.getTestToSources().get("com.example.fixture.FooTest");
        assertNotNull(sourcesForFooTest, "FooTest should be in graph");
        assertTrue(sourcesForFooTest.contains("com.example.fixture.Foo"), "FooTest should reference Foo");
        assertFalse(sourcesForFooTest.contains("com.example.fixture.Bar"), "FooTest should NOT reference Bar");

        // Reverse map: Foo -> FooTest
        Set<String> testsForFoo = graph.getSourceToTests().get("com.example.fixture.Foo");
        assertNotNull(testsForFoo);
        assertTrue(testsForFoo.contains("com.example.fixture.FooTest"));

        // Reverse map: Bar -> no tests (nothing references Bar)
        Set<String> testsForBar = graph.getSourceToTests().get("com.example.fixture.Bar");
        assertTrue(testsForBar == null || testsForBar.isEmpty());
    }

    private void copyFixtureClass(String relativePath, Path targetDir) throws IOException {
        Path dest = targetDir.resolve(relativePath);
        Files.createDirectories(dest.getParent());
        // The fixture .class files are compiled alongside tests; locate via classloader
        try (var in = getClass().getClassLoader().getResourceAsStream(relativePath)) {
            assertNotNull(in, "Fixture class not found on classpath: " + relativePath);
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
