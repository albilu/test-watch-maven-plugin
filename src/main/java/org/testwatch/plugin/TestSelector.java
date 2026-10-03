package org.testwatch.plugin;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Selects tests conservatively; unknown source dependencies always run the full suite. */
public class TestSelector {
    public static final class Result {
        private final boolean all;
        private final Set<String> testFqns;
        private Result(boolean all, Set<String> tests) {
            this.all = all;
            this.testFqns = Collections.unmodifiableSet(new LinkedHashSet<>(tests));
        }
        public boolean isAll() { return all; }
        public Set<String> getTestFqns() { return testFqns; }
        public static Result all() { return new Result(true, Set.of()); }
        public static Result of(Set<String> tests) { return new Result(false, tests); }
    }

    public static Result select(Set<Path> changes, DependencyGraph graph, Path sourceRoot,
            Path testRoot, String patterns) {
        return select(changes, graph, List.of(sourceRoot), List.of(testRoot), patterns);
    }

    static Result select(Set<Path> changes, DependencyGraph graph, List<Path> sourceRoots,
            List<Path> testRoots, String patterns) {
        PathPatterns tests = new PathPatterns(Arrays.asList(patterns.split(",")));
        Set<String> selected = new LinkedHashSet<>();
        for (Path change : changes) {
            Path path = change.toAbsolutePath().normalize();
            Path testRoot = containing(path, testRoots);
            if (testRoot != null && tests.matches(testRoot.relativize(path))) {
                selected.add(pathToFqn(path, testRoot));
                continue;
            }
            Path sourceRoot = containing(path, sourceRoots);
            if (sourceRoot == null || !graph.isComplete()) return Result.all();
            Set<String> affected = graph.testsForSource(sourceRoot.relativize(path));
            if (affected.isEmpty()) return Result.all();
            selected.addAll(affected);
        }
        return selected.isEmpty() ? Result.all() : Result.of(selected);
    }

    private static Path containing(Path path, List<Path> roots) {
        Path best = null;
        for (Path candidate : roots) {
            Path root = candidate.toAbsolutePath().normalize();
            if (path.startsWith(root) && (best == null || root.getNameCount() > best.getNameCount())) best = root;
        }
        return best;
    }

    static String pathToFqn(Path path, Path sourceRoot) {
        Path file = path.toAbsolutePath().normalize();
        Path root = sourceRoot.toAbsolutePath().normalize();
        if (!file.startsWith(root)) throw new IllegalArgumentException("Source is outside its root: " + file);
        String relative = root.relativize(file).toString().replace('\\', '/');
        if (relative.endsWith(".java")) relative = relative.substring(0, relative.length() - 5);
        return relative.replace('/', '.');
    }
}
