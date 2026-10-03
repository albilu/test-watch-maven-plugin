package org.testwatch.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DependencySelectionRegressionTest {
    @TempDir Path base;
    private final List<String> patterns = List.of("**/*Test.java", "**/*Tests.java");

    @Test void followsTransitiveMethodReferenceAndNestedDependencies() throws Exception {
        compile("main", Map.of(
                "sample/Value.java", "package sample; public class Value { public static int value(){return 2;} }",
                "sample/Service.java", "package sample; public class Service { public static int value(){return Value.value();} }"));
        compile("test", Map.of(
                "sample/DirectTest.java", "package sample; public class DirectTest { int value(){return Value.value();} }",
                "sample/ServiceTest.java", "package sample; public class ServiceTest { int value(){return Service.value();} }",
                "sample/ReferenceTest.java", "package sample; public class ReferenceTest { java.util.function.IntSupplier supplier=Value::value; }",
                "sample/NestedTest.java", "package sample; public class NestedTest { class Inside { int value(){return Value.value();} } }"));
        DependencyGraph graph = graph();
        TestSelector.Result result = select(graph, "main/sample/Value.java");
        assertFalse(result.isAll());
        assertEquals(Set.of("sample.DirectTest", "sample.ServiceTest", "sample.ReferenceTest", "sample.NestedTest"), result.getTestFqns());
    }

    @Test void includesImplementationsReachedThroughAnInterface() throws Exception {
        compile("main", Map.of(
                "sample/Api.java", "package sample; public interface Api {int value();}",
                "sample/Impl.java", "package sample; public class Impl implements Api {public int value(){return 2;}}"));
        compile("test", Map.of("sample/ApiTest.java", "package sample; public class ApiTest { int value(Api api){return api.value();} }"));
        assertEquals(Set.of("sample.ApiTest"), select(graph(), "main/sample/Impl.java").getTestFqns());
    }

    @Test void defaultPackageAndEmptyGraphTestChangesRunTheActualClass() throws Exception {
        compile("main", Map.of("Value.java", "public class Value {public static int value(){return 2;}}"));
        compile("test", Map.of("RootTest.java", "public class RootTest { int value(){return Value.value();} }"));
        assertEquals(Set.of("RootTest"), graph().getTestToSources().keySet());
        assertEquals(Set.of("RootTest"), select(graph(), "test/RootTest.java").getTestFqns());
        assertEquals(Set.of("RootTest"), select(DependencyGraph.empty(), "test/RootTest.java").getTestFqns());
    }

    @Test void unsupportedBytecodeFallsBackToTheFullSuite() throws Exception {
        compile("main", Map.of("Value.java", "public class Value {}"));
        compile("test", Map.of("RootTest.java", "public class RootTest { Value value; }"));
        Path classFile = base.resolve("test-classes/RootTest.class");
        byte[] bytes = Files.readAllBytes(classFile);
        bytes[6] = 0;
        bytes[7] = 100;
        Files.write(classFile, bytes);
        assertTrue(select(graph(), "main/Value.java").isAll());
    }

    @Test void aChangedCompileTimeConstantCannotUseAPartialSelection() throws Exception {
        compile("main", Map.of(
                "Value.java", "public class Value {public static final int LIMIT=2; public static int value(){return 2;}}",
                "Other.java", "public class Other {public static int value(){return 1;}}"));
        compile("test", Map.of(
                "DirectTest.java", "public class DirectTest {int value(){return Value.value();}}",
                "ConstantTest.java", "public class ConstantTest {int value(){return Other.value()+Value.LIMIT;}}"));
        assertTrue(select(graph(), "main/Value.java").isAll());
    }

    private DependencyGraph graph() throws Exception {
        return DependencyGraph.build(base.resolve("main-classes"), base.resolve("test-classes"), patterns);
    }

    private TestSelector.Result select(DependencyGraph graph, String changed) {
        return TestSelector.select(Set.of(base.resolve(changed)), graph, base.resolve("main"), base.resolve("test"), "**/*Test.java,**/*Tests.java");
    }

    private void compile(String kind, Map<String, String> sources) throws Exception {
        Path output = Files.createDirectories(base.resolve(kind + "-classes"));
        List<String> arguments = new ArrayList<>(List.of("-proc:none", "--release", "11", "-d", output.toString(), "-classpath", base.resolve("main-classes").toString()));
        for (Map.Entry<String, String> source : sources.entrySet()) {
            Path file = base.resolve(kind).resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            arguments.add(file.toString());
        }
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, arguments.toArray(new String[0])));
    }
}
