package org.twelve.meridian.python;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Negative / positive corpus for generalized Python typing.
 *
 * <p>Rules under test are Meridian semantics, not TypeEvalPy EXACT tokens.
 * A string literal key like {@code 'foo'} is typed {@code str}; that does
 * <em>not</em> authorize inventing a fixed key name {@code 'a'} from a micro-benchmark.
 */
@Execution(ExecutionMode.SAME_THREAD)
class GeneralizationRegressionTest {

    private static PythonInferencer inferencer;

    @TempDir Path tmp;

    @BeforeAll
    static void setup() {
        inferencer = new PythonInferencer();
    }

    @Test
    void string_literal_key_is_str_and_projects_that_key() throws Exception {
        Path py = tmp.resolve("keys.py");
        Files.writeString(py, """
                d = {'foo': 1, 'bar': 2}
                x = d['foo']
                """);
        PythonInferencer.InferResult r = inferencer.inferFileDetailed(py.toFile());
        List<Map<String, Object>> sites =
                new TypeEvalPySiteExporter().collect(r.inference());

        assertTrue(sites.stream().anyMatch(s -> "d['foo']".equals(s.get("variable"))),
                () -> "literal key 'foo' should project d['foo']: " + sites);
        assertTrue(sites.stream().noneMatch(s -> {
            Object v = s.get("variable");
            return v instanceof String name && name.contains("['a']");
        }), () -> "must not invent benchmark key 'a': " + sites);
    }

    @Test
    void returned_dict_without_known_keys_does_not_invent_a() throws Exception {
        Path py = tmp.resolve("ret_dict.py");
        Files.writeString(py, """
                def make():
                    return {}

                m = make()
                """);
        PythonInferencer.InferResult r = inferencer.inferFileDetailed(py.toFile());
        List<Map<String, Object>> sites =
                new TypeEvalPySiteExporter().collect(r.inference());

        assertTrue(sites.stream().noneMatch(s -> "m['a']".equals(s.get("variable"))),
                () -> "empty/unknown dict must not invent m['a']: " + sites);
    }

    @Test
    void returned_dict_with_non_a_key_projects_that_key_not_a() throws Exception {
        Path py = tmp.resolve("ret_keyed.py");
        Files.writeString(py, """
                def make():
                    d = {'z': 'hello'}
                    return d

                m = make()
                """);
        PythonInferencer.InferResult r = inferencer.inferFileDetailed(py.toFile());
        List<Map<String, Object>> sites =
                new TypeEvalPySiteExporter().collect(r.inference());

        Set<String> vars = sites.stream()
                .map(s -> s.get("variable"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toSet());
        assertTrue(vars.contains("m['z']") || vars.contains("d['z']"),
                () -> "should project known key 'z': " + vars);
        assertFalse(vars.contains("m['a']"), () -> "must not invent m['a']: " + vars);
    }

    @Test
    void two_classes_same_method_name_keep_distinct_returns() {
        String src = """
                class A:
                    def run(self):
                        return 1

                class B:
                    def run(self):
                        return "x"
                """;
        PythonInferenceResult inf = new PythonInferencer().inferDetailed(src);
        assertEquals(List.of("int"), inf.methodReturns().get("A.run"));
        assertEquals(List.of("str"), inf.methodReturns().get("B.run"));
        assertNotEquals(inf.methodReturns().get("A.run"), inf.methodReturns().get("B.run"));
    }

    @Test
    void two_modules_same_function_name_use_import_alias() {
        PythonInferencer local = new PythonInferencer();
        local.registerModule("mod_a", "def f():\n    return 1\n");
        local.registerModule("mod_b", "def f():\n    return 'hi'\n");
        PythonInferenceResult inf = local.inferDetailed("""
                from mod_a import f as fa
                from mod_b import f as fb
                x = fa()
                y = fb()
                """);
        assertEquals(List.of("int"), inf.callResults().get("x"),
                () -> String.valueOf(inf.callResults()));
        assertEquals(List.of("str"), inf.callResults().get("y"),
                () -> String.valueOf(inf.callResults()));
        List<Map<String, Object>> sites = new TypeEvalPySiteExporter().collect(inf);
        Map<String, Object> x = sites.stream()
                .filter(s -> "x".equals(s.get("variable")))
                .findFirst()
                .orElse(null);
        Map<String, Object> y = sites.stream()
                .filter(s -> "y".equals(s.get("variable")))
                .findFirst()
                .orElse(null);
        assertNotNull(x, () -> String.valueOf(sites));
        assertNotNull(y, () -> String.valueOf(sites));
        assertTrue(((List<?>) x.get("type")).contains("int"), () -> String.valueOf(x));
        assertTrue(((List<?>) y.get("type")).contains("str"), () -> String.valueOf(y));
    }

    @Test
    void self_attr_delegation_reaches_sites_fr() throws Exception {
        Path py = tmp.resolve("delegate.py");
        Files.writeString(py, """
                class A:
                    def helper(self):
                        return "hi"

                    def __init__(self):
                        self.cb = self.helper

                    def run(self):
                        return self.cb()
                """);
        PythonInferencer.InferResult r = new PythonInferencer().inferFileDetailed(py.toFile());
        assertEquals(List.of("str"), r.inference().methodReturns().get("A.run"));
        List<Map<String, Object>> sites = new TypeEvalPySiteExporter().collect(r.inference());
        assertTrue(sites.stream().anyMatch(s ->
                        "A.run".equals(s.get("function"))
                                && !s.containsKey("parameter")
                                && !s.containsKey("variable")
                                && ((List<?>) s.get("type")).contains("str")),
                () -> String.valueOf(sites));
    }

    @Test
    void lambda_returning_str_is_not_forced_int() {
        String stub = inferencer.toStub("f = lambda: 'hi'\n");
        assertFalse(stub.matches("(?s).*f:.*int.*"),
                () -> "lambda returning str must not default to int: " + stub);
    }

    @Test
    void augassign_on_str_is_not_forced_int() {
        String stub = inferencer.toStub("""
                s = "a"
                s += "b"
                """);
        assertTrue(stub.contains("str") || stub.contains("String"),
                () -> "str accumulation should stay string-shaped: " + stub);
        assertFalse(stub.contains("s: int"), () -> stub);
    }
}
