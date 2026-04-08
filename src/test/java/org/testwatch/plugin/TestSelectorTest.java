package org.testwatch.plugin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testwatch.plugin.DependencyGraph;
import org.testwatch.plugin.TestSelector;
import org.testwatch.plugin.model.WatchEventType;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TestSelectorTest {

    private DependencyGraph graph;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Set<String>> testToSources = new LinkedHashMap<>();
        testToSources.put("org.testwatch.FooTest", new LinkedHashSet<>(Set.of("org.testwatch.Foo")));
        testToSources.put("org.testwatch.BarTest", new LinkedHashSet<>(Set.of("org.testwatch.Bar")));

        Map<String, Set<String>> sourceToTests = new LinkedHashMap<>();
        sourceToTests.put("org.testwatch.Foo", new LinkedHashSet<>(Set.of("org.testwatch.FooTest")));
        sourceToTests.put("org.testwatch.Bar", new LinkedHashSet<>(Set.of("org.testwatch.BarTest")));

        graph = DependencyGraph.ofMaps(testToSources, sourceToTests);
    }

    @Test
    void changedSourceFile_returnsMatchingTest() {
        // src/main/java/org/testwatch/Foo.java changed
        Path changed = Path.of("src/main/java/org/testwatch/Foo.java");
        TestSelector.Result result = TestSelector.select(Set.of(changed), graph,
                Path.of("src/main/java"), Path.of("src/test/java"), "**/*Test.java,**/*Tests.java");

        assertFalse(result.isAll());
        assertEquals(Set.of("org.testwatch.FooTest"), result.getTestFqns());
    }

    @Test
    void changedUnknownFile_returnsAll() {
        // A new file not yet in the graph
        Path changed = Path.of("src/main/java/org/testwatch/New.java");
        TestSelector.Result result = TestSelector.select(Set.of(changed), graph,
                Path.of("src/main/java"), Path.of("src/test/java"), "**/*Test.java,**/*Tests.java");

        assertTrue(result.isAll(), "Unknown file should trigger full suite run");
    }

    @Test
    void multipleChangedFiles_returnsUnionOfTests() {
        Path foo = Path.of("src/main/java/org/testwatch/Foo.java");
        Path bar = Path.of("src/main/java/org/testwatch/Bar.java");
        TestSelector.Result result = TestSelector.select(Set.of(foo, bar), graph,
                Path.of("src/main/java"), Path.of("src/test/java"), "**/*Test.java,**/*Tests.java");

        assertFalse(result.isAll());
        assertEquals(Set.of("org.testwatch.FooTest", "org.testwatch.BarTest"), result.getTestFqns());
    }

    @Test
    void changedTestFile_runsTestDirectly() {
        // src/test/java/org/testwatch/FooTest.java changed — should run FooTest
        // directly
        Path changed = Path.of("src/test/java/org/testwatch/FooTest.java");
        TestSelector.Result result = TestSelector.select(
                Set.of(changed), graph,
                Path.of("src/main/java"),
                Path.of("src/test/java"),
                "**/*Test.java,**/*Tests.java");

        assertFalse(result.isAll(), "Changed test file should not trigger full suite");
        assertEquals(Set.of("org.testwatch.FooTest"), result.getTestFqns());
    }

    @Test
    void changedTestFile_absolutePath_runsTestDirectly() {
        // Absolute path as FileWatcherService would produce
        Path base = Path.of("/project");
        Path changed = base.resolve("src/test/java/org/testwatch/FooTest.java");
        TestSelector.Result result = TestSelector.select(
                Set.of(changed), graph,
                base.resolve("src/main/java"),
                base.resolve("src/test/java"),
                "**/*Test.java,**/*Tests.java");

        assertFalse(result.isAll());
        assertEquals(Set.of("org.testwatch.FooTest"), result.getTestFqns());
    }
}
