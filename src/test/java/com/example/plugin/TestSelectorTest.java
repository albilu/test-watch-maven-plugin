package com.example.plugin;

import com.example.plugin.model.WatchEventType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TestSelectorTest {

    private DependencyGraph graph;

    @BeforeEach
    void setUp() throws Exception {
        Map<String, Set<String>> testToSources = new LinkedHashMap<>();
        testToSources.put("com.example.FooTest", new LinkedHashSet<>(Set.of("com.example.Foo")));
        testToSources.put("com.example.BarTest", new LinkedHashSet<>(Set.of("com.example.Bar")));

        Map<String, Set<String>> sourceToTests = new LinkedHashMap<>();
        sourceToTests.put("com.example.Foo", new LinkedHashSet<>(Set.of("com.example.FooTest")));
        sourceToTests.put("com.example.Bar", new LinkedHashSet<>(Set.of("com.example.BarTest")));

        graph = DependencyGraph.ofMaps(testToSources, sourceToTests);
    }

    @Test
    void changedSourceFile_returnsMatchingTest() {
        // src/main/java/com/example/Foo.java changed
        Path changed = Path.of("src/main/java/com/example/Foo.java");
        TestSelector.Result result = TestSelector.select(Set.of(changed), graph,
            Path.of("src/main/java"), Path.of("src/test/java"), "**/*Test.java,**/*Tests.java");

        assertFalse(result.isAll());
        assertEquals(Set.of("com.example.FooTest"), result.getTestFqns());
    }

    @Test
    void changedUnknownFile_returnsAll() {
        // A new file not yet in the graph
        Path changed = Path.of("src/main/java/com/example/New.java");
        TestSelector.Result result = TestSelector.select(Set.of(changed), graph,
            Path.of("src/main/java"), Path.of("src/test/java"), "**/*Test.java,**/*Tests.java");

        assertTrue(result.isAll(), "Unknown file should trigger full suite run");
    }

    @Test
    void multipleChangedFiles_returnsUnionOfTests() {
        Path foo = Path.of("src/main/java/com/example/Foo.java");
        Path bar = Path.of("src/main/java/com/example/Bar.java");
        TestSelector.Result result = TestSelector.select(Set.of(foo, bar), graph,
            Path.of("src/main/java"), Path.of("src/test/java"), "**/*Test.java,**/*Tests.java");

        assertFalse(result.isAll());
        assertEquals(Set.of("com.example.FooTest", "com.example.BarTest"), result.getTestFqns());
    }

    @Test
    void changedTestFile_runsTestDirectly() {
        // src/test/java/com/example/FooTest.java changed — should run FooTest directly
        Path changed = Path.of("src/test/java/com/example/FooTest.java");
        TestSelector.Result result = TestSelector.select(
            Set.of(changed), graph,
            Path.of("src/main/java"),
            Path.of("src/test/java"),
            "**/*Test.java,**/*Tests.java");

        assertFalse(result.isAll(), "Changed test file should not trigger full suite");
        assertEquals(Set.of("com.example.FooTest"), result.getTestFqns());
    }

    @Test
    void changedTestFile_absolutePath_runsTestDirectly() {
        // Absolute path as FileWatcherService would produce
        Path base = Path.of("/project");
        Path changed = base.resolve("src/test/java/com/example/FooTest.java");
        TestSelector.Result result = TestSelector.select(
            Set.of(changed), graph,
            base.resolve("src/main/java"),
            base.resolve("src/test/java"),
            "**/*Test.java,**/*Tests.java");

        assertFalse(result.isAll());
        assertEquals(Set.of("com.example.FooTest"), result.getTestFqns());
    }
}
