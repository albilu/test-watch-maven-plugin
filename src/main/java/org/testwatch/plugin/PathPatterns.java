package org.testwatch.plugin;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;

/** Maven-style globs, including zero directories for a leading double star. */
final class PathPatterns {
    private final List<PathMatcher> matchers = new ArrayList<>();

    PathPatterns(List<String> patterns) {
        for (String pattern : patterns) {
            String glob = pattern.trim();
            if (glob.isEmpty()) continue;
            matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob));
            if (glob.startsWith("**/")) {
                matchers.add(FileSystems.getDefault().getPathMatcher("glob:" + glob.substring(3)));
            }
        }
    }

    boolean isEmpty() { return matchers.isEmpty(); }

    boolean matches(Path path) {
        return matchers.stream().anyMatch(m -> m.matches(path) || m.matches(path.getFileName()));
    }
}
