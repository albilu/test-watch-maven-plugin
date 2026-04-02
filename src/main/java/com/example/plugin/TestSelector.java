package com.example.plugin;

import java.nio.file.*;
import java.util.*;

/**
 * Selects which test classes to run given a set of changed .java file paths
 * and the current DependencyGraph.
 */
public class TestSelector {

    public static final class Result {
        private final boolean all;
        private final Set<String> testFqns;

        private Result(boolean all, Set<String> testFqns) {
            this.all = all;
            this.testFqns = Collections.unmodifiableSet(testFqns);
        }

        public boolean isAll() { return all; }
        public Set<String> getTestFqns() { return testFqns; }

        public static Result all() { return new Result(true, Collections.emptySet()); }
        public static Result of(Set<String> fqns) { return new Result(false, fqns); }
    }

    /**
     * @param changedFiles   absolute or project-relative paths to changed .java files
     * @param graph          current dependency graph (may be empty on first run)
     * @param sourceRoot     path to src/main/java (used to derive FQN from path)
     * @param testPatterns   comma-separated glob string, used to detect if a changed file
     *                       is itself a test (in which case it runs directly)
     * @return Result.all() if any changed file is unknown; Result.of(fqns) otherwise
     */
    public static Result select(Set<Path> changedFiles, DependencyGraph graph,
                                Path sourceRoot, String testPatterns) {
        if (graph.getSourceToTests().isEmpty() && graph.getTestToSources().isEmpty()) {
            // Empty graph — no compiled classes yet; trigger full suite
            return Result.all();
        }

        List<PathMatcher> testMatchers = Arrays.stream(testPatterns.split(","))
            .map(String::trim)
            .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p))
            .toList();

        Set<String> selected = new LinkedHashSet<>();
        for (Path changed : changedFiles) {
            String fqn = pathToFqn(changed, sourceRoot);

            // If the changed file is itself a test class, run it directly
            String simpleName = changed.getFileName().toString();
            String relJava = changed.toString().replace('\\', '/');
            Path simplePath = Path.of(simpleName);
            Path relPath = Path.of(relJava);
            boolean isTest = testMatchers.stream()
                .anyMatch(m -> m.matches(simplePath) || m.matches(relPath));
            if (isTest) {
                // Convert test file path to FQN (lives under src/test/java, use same logic)
                selected.add(fqn);
                continue;
            }

            Set<String> tests = graph.getSourceToTests().get(fqn);
            if (tests == null || tests.isEmpty()) {
                // Changed file has no known test dependents — trigger full suite
                return Result.all();
            }
            selected.addAll(tests);
        }

        return selected.isEmpty() ? Result.all() : Result.of(selected);
    }

    /**
     * Convert a .java file path to a fully-qualified class name.
     * Works with both absolute paths and project-relative paths.
     * e.g. src/main/java/com/example/Foo.java -> com.example.Foo
     */
    static String pathToFqn(Path path, Path sourceRoot) {
        // Normalise to forward-slash relative path string
        Path normalised = path.normalize();
        Path rel;
        try {
            rel = sourceRoot.normalize().relativize(normalised);
        } catch (IllegalArgumentException e) {
            // path is not under sourceRoot — use the filename stem as a best-effort FQN
            String name = normalised.getFileName().toString();
            return name.endsWith(".java") ? name.substring(0, name.length() - 5) : name;
        }
        String s = rel.toString().replace('\\', '/');
        if (s.endsWith(".java")) s = s.substring(0, s.length() - 5);
        return s.replace('/', '.');
    }
}
