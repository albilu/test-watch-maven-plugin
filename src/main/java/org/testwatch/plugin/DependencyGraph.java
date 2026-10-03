package org.testwatch.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Conservative, transitive dependencies across production classes and test helpers. */
public class DependencyGraph {
    private static final Logger LOG = Logger.getLogger(DependencyGraph.class.getName());
    private final Map<String, Set<String>> testToSources;
    private final Map<String, Set<String>> sourceToTests;
    private final Map<String, Set<String>> sourceFiles;
    private final Set<String> constants;
    private final boolean complete;

    private DependencyGraph(Map<String, Set<String>> tests, Map<String, Set<String>> sources,
            Map<String, Set<String>> sourceFiles, Set<String> constants, boolean complete) {
        this.testToSources = freeze(tests);
        this.sourceToTests = freeze(sources);
        this.sourceFiles = freeze(sourceFiles);
        this.constants = Set.copyOf(constants);
        this.complete = complete;
    }

    private static Map<String, Set<String>> freeze(Map<String, Set<String>> values) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, Collections.unmodifiableSet(new LinkedHashSet<>(value))));
        return Collections.unmodifiableMap(copy);
    }

    public Map<String, Set<String>> getTestToSources() { return testToSources; }
    public Map<String, Set<String>> getSourceToTests() { return sourceToTests; }
    public boolean isComplete() { return complete; }

    Set<String> testsForSource(Path relativeJavaPath) {
        String file = relativeJavaPath.toString().replace('\\', '/');
        Set<String> classes = sourceFiles.get(file);
        if (classes == null) {
            String fqn = file.replace('/', '.').replaceFirst("\\.java$", "");
            classes = Set.of(fqn);
        }
        Set<String> tests = new LinkedHashSet<>();
        for (String source : classes) {
            // The compiler erases dependencies on compile-time constants.
            if (constants.contains(source)) return Set.of();
            tests.addAll(sourceToTests.getOrDefault(source, Set.of()));
        }
        return tests;
    }

    public static DependencyGraph empty() {
        return new DependencyGraph(Map.of(), Map.of(), Map.of(), Set.of(), false);
    }

    static DependencyGraph ofMaps(Map<String, Set<String>> tests, Map<String, Set<String>> sources) {
        return new DependencyGraph(tests, sources, Map.of(), Set.of(), true);
    }

    public static DependencyGraph build(Path classes, Path testClasses, List<String> patterns) throws IOException {
        return buildDirectories(List.of(classes), List.of(testClasses), patterns);
    }

    static DependencyGraph build(List<ProjectLayout> layouts, List<String> patterns) throws IOException {
        List<Path> classes = new ArrayList<>(), tests = new ArrayList<>();
        for (ProjectLayout layout : layouts) {
            classes.add(layout.classes);
            tests.add(layout.testClasses);
        }
        return buildDirectories(classes, tests, patterns);
    }

    private static DependencyGraph buildDirectories(List<Path> mainDirs, List<Path> testDirs,
            List<String> patterns) throws IOException {
        Map<String, ClassInfo> classes = new LinkedHashMap<>();
        Set<String> sources = new LinkedHashSet<>(), tests = new LinkedHashSet<>();
        boolean complete = true;
        PathPatterns matchers = new PathPatterns(patterns);
        for (Path directory : mainDirs) complete &= scan(directory, classes, sources, null, matchers);
        for (Path directory : testDirs) complete &= scan(directory, classes, null, tests, matchers);

        // A reference to a base class or interface can dispatch to any project implementation.
        for (ClassInfo info : classes.values()) {
            for (String parent : info.parents) {
                ClassInfo base = classes.get(parent);
                if (base != null) base.references.add(info.name);
            }
        }
        Map<String, Set<String>> testToSources = new LinkedHashMap<>();
        Map<String, Set<String>> sourceToTests = new LinkedHashMap<>();
        for (String test : tests) {
            Set<String> reached = new LinkedHashSet<>();
            Deque<String> pending = new ArrayDeque<>();
            pending.add(test);
            classes.keySet().stream().filter(n -> n.startsWith(test + "$")) .forEach(pending::add);
            boolean dynamic = false;
            while (!pending.isEmpty()) {
                String name = pending.removeFirst();
                if (!reached.add(name)) continue;
                ClassInfo info = classes.get(name);
                if (info == null) continue;
                dynamic |= info.dynamic;
                pending.addAll(info.references);
            }
            reached.retainAll(sources);
            testToSources.put(test, reached);
            // Reflection/framework discovery and unlinked tests cannot establish a safe subset.
            if (dynamic || reached.isEmpty()) complete = false;
            for (String source : reached) {
                sourceToTests.computeIfAbsent(source, key -> new LinkedHashSet<>()).add(test);
            }
        }
        Map<String, Set<String>> sourceFiles = new LinkedHashMap<>();
        Set<String> constants = new LinkedHashSet<>();
        for (String source : sources) {
            ClassInfo info = classes.get(source);
            if (info == null) continue;
            if (info.constant) constants.add(source);
            if (info.sourceFile != null) {
                int dot = source.lastIndexOf('.');
                String file = (dot < 0 ? "" : source.substring(0, dot).replace('.', '/') + "/") + info.sourceFile;
                sourceFiles.computeIfAbsent(file, key -> new LinkedHashSet<>()).add(source);
            }
        }
        return new DependencyGraph(testToSources, sourceToTests, sourceFiles, constants, complete && !tests.isEmpty());
    }

    private static boolean scan(Path directory, Map<String, ClassInfo> classes, Set<String> sources,
            Set<String> tests, PathPatterns patterns) throws IOException {
        if (!Files.isDirectory(directory)) return true;
        boolean complete = true;
        try (var files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".class"))::iterator) {
                try {
                    ClassInfo info = read(file);
                    if (classes.putIfAbsent(info.name, info) != null) complete = false;
                    if (sources != null) sources.add(info.name);
                    if (tests != null && info.name.indexOf('$') < 0) {
                        Path javaPath = Path.of(directory.relativize(file).toString().replaceAll("\\.class$", ".java"));
                        if (patterns.matches(javaPath)) tests.add(info.name);
                    }
                } catch (IOException | RuntimeException e) {
                    complete = false;
                    LOG.warning("Cannot analyze " + file + "; source changes will run all tests: " + e.getMessage());
                }
            }
        }
        return complete;
    }

    private static final class ClassInfo {
        String name;
        String sourceFile;
        boolean dynamic;
        boolean constant;
        final Set<String> references = new LinkedHashSet<>();
        final Set<String> parents = new LinkedHashSet<>();
        void name(String name) {
            if (name == null) return;
            if (name.startsWith("[")) descriptor(name);
            else references.add(name.replace('/', '.'));
        }
        void descriptor(String descriptor) {
            if (descriptor == null) return;
            for (int i = 0; i < descriptor.length(); i++) {
                if (descriptor.charAt(i) == 'L') {
                    int end = descriptor.indexOf(';', i);
                    if (end < 0) break;
                    name(descriptor.substring(i + 1, end));
                    i = end;
                }
            }
        }
        void constant(Object value) {
            if (value instanceof Type) descriptor(((Type) value).getDescriptor());
            else if (value instanceof Handle) {
                Handle handle = (Handle) value;
                name(handle.getOwner());
                descriptor(handle.getDesc());
            } else if (value instanceof ConstantDynamic) {
                ConstantDynamic dynamic = (ConstantDynamic) value;
                descriptor(dynamic.getDescriptor());
                constant(dynamic.getBootstrapMethod());
                for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) constant(dynamic.getBootstrapMethodArgument(i));
            }
        }
        AnnotationVisitor annotation(String descriptor) {
            descriptor(descriptor);
            if (!descriptor.startsWith("Lorg/junit/") && !descriptor.startsWith("Ljava/lang/")) dynamic = true;
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override public void visit(String name, Object value) { constant(value); }
                @Override public void visitEnum(String name, String descriptor, String value) { descriptor(descriptor); }
                @Override public AnnotationVisitor visitAnnotation(String name, String descriptor) { return annotation(descriptor); }
                @Override public AnnotationVisitor visitArray(String name) { return this; }
            };
        }
    }

    private static ClassInfo read(Path file) throws IOException {
        ClassInfo info = new ClassInfo();
        new ClassReader(Files.readAllBytes(file)).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int version, int access, String name, String signature, String parent, String[] interfaces) {
                info.name = name.replace('/', '.');
                if (parent != null) info.parents.add(parent.replace('/', '.'));
                if (interfaces != null) for (String iface : interfaces) info.parents.add(iface.replace('/', '.'));
                info.references.addAll(info.parents);
            }
            @Override public void visitSource(String source, String debug) { info.sourceFile = source; }
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { return info.annotation(descriptor); }
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                info.descriptor(descriptor);
                if (value != null && (access & Opcodes.ACC_STATIC) != 0 && (access & Opcodes.ACC_FINAL) != 0) info.constant = true;
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { return info.annotation(descriptor); }
                };
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                info.descriptor(descriptor);
                if (exceptions != null) for (String exception : exceptions) info.name(exception);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) { return info.annotation(descriptor); }
                    @Override public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor, boolean visible) { return info.annotation(descriptor); }
                    @Override public void visitTypeInsn(int opcode, String type) { info.name(type); }
                    @Override public void visitFieldInsn(int opcode, String owner, String name, String descriptor) { info.name(owner); info.descriptor(descriptor); }
                    @Override public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean itf) {
                        info.name(owner);
                        info.descriptor(descriptor);
                        if (owner.startsWith("java/lang/reflect/") || owner.equals("java/lang/Class")
                                || owner.equals("java/util/ServiceLoader")) info.dynamic = true;
                    }
                    @Override public void visitLdcInsn(Object value) { info.constant(value); }
                    @Override public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap, Object... arguments) {
                        info.descriptor(descriptor);
                        info.constant(bootstrap);
                        for (Object argument : arguments) info.constant(argument);
                    }
                    @Override public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                            org.objectweb.asm.Label handler, String type) { info.name(type); }
                    @Override public void visitMultiANewArrayInsn(String descriptor, int dimensions) { info.descriptor(descriptor); }
                };
            }
        }, ClassReader.SKIP_FRAMES);
        return info;
    }
}
