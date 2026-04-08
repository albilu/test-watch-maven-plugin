package org.testwatch.plugin;

import org.objectweb.asm.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.logging.Logger;

/**
 * Builds a bidirectional dependency map between test classes and source classes
 * by parsing compiled .class files with ASM.
 *
 * <p>
 * Walks target/test-classes for test classes (identified by testPattern globs
 * applied to the simple class name), then for each test class uses ASM to
 * collect
 * all referenced class FQNs that also appear in target/classes.
 * </p>
 */
public class DependencyGraph {

    private static final Logger LOG = Logger.getLogger(DependencyGraph.class.getName());

    /** test FQN → set of source FQNs it references */
    private final Map<String, Set<String>> testToSources;
    /** source FQN → set of test FQNs that reference it */
    private final Map<String, Set<String>> sourceToTests;

    private DependencyGraph(Map<String, Set<String>> testToSources,
            Map<String, Set<String>> sourceToTests) {
        this.testToSources = Collections.unmodifiableMap(testToSources);
        this.sourceToTests = Collections.unmodifiableMap(sourceToTests);
    }

    public Map<String, Set<String>> getTestToSources() {
        return testToSources;
    }

    public Map<String, Set<String>> getSourceToTests() {
        return sourceToTests;
    }

    /**
     * Build a DependencyGraph by scanning the given class output directories.
     *
     * @param classesDir     path to target/classes (source classes)
     * @param testClassesDir path to target/test-classes (test + source classes
     *                       compiled from src/test)
     * @param testPatterns   comma-split list of glob patterns identifying test
     *                       class simple names
     *                       e.g. ["**&#47;*Test.java", "**&#47;*Tests.java"]
     */
    public static DependencyGraph build(Path classesDir, Path testClassesDir,
            List<String> testPatterns) throws IOException {
        // 1. Collect all source FQNs from target/classes
        Set<String> sourceFqns = collectFqns(classesDir);

        // 2. Collect all test FQNs from target/test-classes, filtered by testPatterns
        Set<String> testFqns = collectTestFqns(testClassesDir, testPatterns);

        // 3. For each test class, use ASM to find which source FQNs it references
        Map<String, Set<String>> testToSources = new LinkedHashMap<>();
        for (String testFqn : testFqns) {
            Path classFile = testClassesDir.resolve(toClassPath(testFqn));
            if (!Files.exists(classFile))
                continue;
            Set<String> refs = collectReferences(classFile, sourceFqns);
            if (!refs.isEmpty()) {
                testToSources.put(testFqn, refs);
            }
        }

        // 4. Build reverse map
        Map<String, Set<String>> sourceToTests = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : testToSources.entrySet()) {
            for (String src : entry.getValue()) {
                sourceToTests.computeIfAbsent(src, k -> new LinkedHashSet<>()).add(entry.getKey());
            }
        }

        return new DependencyGraph(testToSources, sourceToTests);
    }

    /** Returns an empty graph (used when target/ dirs do not exist yet). */
    public static DependencyGraph empty() {
        return new DependencyGraph(Collections.emptyMap(), Collections.emptyMap());
    }

    /** Package-private factory for tests. */
    static DependencyGraph ofMaps(Map<String, Set<String>> testToSources,
            Map<String, Set<String>> sourceToTests) {
        return new DependencyGraph(testToSources, sourceToTests);
    }

    // ---- helpers ----

    private static Set<String> collectFqns(Path dir) throws IOException {
        Set<String> fqns = new LinkedHashSet<>();
        if (!Files.exists(dir))
            return fqns;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".class")) {
                    fqns.add(toFqn(dir, file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return fqns;
    }

    private static Set<String> collectTestFqns(Path dir, List<String> patterns) throws IOException {
        Set<String> fqns = new LinkedHashSet<>();
        if (!Files.exists(dir))
            return fqns;
        List<PathMatcher> matchers = patterns.stream()
                .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p))
                .toList();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!file.toString().endsWith(".class"))
                    return FileVisitResult.CONTINUE;
                // Build a relative path with .java extension for glob matching
                // e.g. org/testwatch/FooTest.class -> org/testwatch/FooTest.java
                String relJava = dir.relativize(file).toString()
                        .replace('\\', '/')
                        .replace(".class", ".java");
                Path relPath = Path.of(relJava);
                // Also check simple filename alone for patterns like *Test.java
                Path simplePath = Path.of(file.getFileName().toString().replace(".class", ".java"));
                boolean isTest = matchers.stream().anyMatch(m -> m.matches(relPath) || m.matches(simplePath));
                if (isTest) {
                    fqns.add(toFqn(dir, file));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return fqns;
    }

    private static Set<String> collectReferences(Path classFile, Set<String> knownSources) {
        Set<String> refs = new LinkedHashSet<>();
        try (InputStream in = Files.newInputStream(classFile)) {
            ClassReader reader = new ClassReader(in);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature,
                        String superName, String[] interfaces) {
                    addIfKnown(name, knownSources, refs);
                    addIfKnown(superName, knownSources, refs);
                    if (interfaces != null) {
                        for (String iface : interfaces)
                            addIfKnown(iface, knownSources, refs);
                    }
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                        String signature, Object value) {
                    collectFromDescriptor(descriptor, knownSources, refs);
                    return null;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    collectFromDescriptor(descriptor, knownSources, refs);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitTypeInsn(int opcode, String type) {
                            addIfKnown(type, knownSources, refs);
                        }

                        @Override
                        public void visitFieldInsn(int opcode, String owner, String name2, String desc) {
                            addIfKnown(owner, knownSources, refs);
                            collectFromDescriptor(desc, knownSources, refs);
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name2,
                                String desc, boolean itf) {
                            addIfKnown(owner, knownSources, refs);
                            collectFromDescriptor(desc, knownSources, refs);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            LOG.warning("ASM: skipping unreadable class file: " + classFile + " — " + e.getMessage());
        }
        return refs;
    }

    private static void addIfKnown(String internalName, Set<String> known, Set<String> out) {
        if (internalName == null)
            return;
        String fqn = internalName.replace('/', '.');
        if (known.contains(fqn))
            out.add(fqn);
    }

    private static void collectFromDescriptor(String descriptor, Set<String> known, Set<String> out) {
        if (descriptor == null)
            return;
        // Extract class names from JVM type descriptors, e.g. "Lorg/testwatch/Foo;" ->
        // "org.testwatch.Foo"
        int i = 0;
        while (i < descriptor.length()) {
            if (descriptor.charAt(i) == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0)
                    break;
                addIfKnown(descriptor.substring(i + 1, end), known, out);
                i = end + 1;
            } else {
                i++;
            }
        }
    }

    private static String toFqn(Path base, Path classFile) {
        String rel = base.relativize(classFile).toString()
                .replace(FileSystems.getDefault().getSeparator(), ".")
                .replace("/", ".");
        if (rel.endsWith(".class"))
            rel = rel.substring(0, rel.length() - 6);
        return rel;
    }

    private static String toClassPath(String fqn) {
        return fqn.replace('.', '/') + ".class";
    }
}
