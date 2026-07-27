package org.twelve.meridian.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PythonSemanticRefinerTest {

    @TempDir Path tmp;

    @Test
    void callSiteArgsRefineFunctionParams() {
        PythonInferenceResult r = new PythonInferencer().inferDetailed("""
                def add(a, b):
                    return a + b

                x = add(1, 2)
                """);
        Map<String, List<String>> params = r.refinedParams().get("add");
        assertNotNull(params, () -> String.valueOf(r.refinedParams()));
        assertEquals(List.of("int"), params.get("a"));
        assertEquals(List.of("int"), params.get("b"));
        assertEquals("int", r.annotationHints().get("add#a"));
    }

    @Test
    void containerLiteralProjectsConcreteKeys() {
        PythonInferenceResult r = new PythonInferencer().inferDetailed("""
                d = {'foo': 1, 'bar': 'x'}
                xs = [1, 'a']
                """);
        assertEquals(List.of("int"), r.containerElements().get("d['foo']"));
        assertEquals(List.of("str"), r.containerElements().get("d['bar']"));
        assertEquals(List.of("int"), r.containerElements().get("xs[0]"));
        assertEquals(List.of("str"), r.containerElements().get("xs[1]"));
        assertFalse(r.containerElements().containsKey("d['a']"));
    }

    @Test
    void returnedCallableBindsThroughAssignment() {
        PythonInferenceResult r = new PythonInferencer().inferDetailed("""
                def other():
                    return 1

                def make():
                    return other

                x = make()
                y = x()
                """);
        assertEquals(List.of("int"), r.callReturns().get("x"),
                () -> String.valueOf(r.callReturns()));
        assertEquals(List.of("int"), r.callResults().get("y"),
                () -> String.valueOf(r.callResults()));
    }

    @Test
    void receiverSensitiveMethodDoesNotFirstMatch() {
        PythonInferenceResult r = new PythonInferencer().inferDetailed("""
                class A:
                    def run(self):
                        return 1

                class B:
                    def run(self):
                        return "x"

                a = A()
                b = B()
                xa = a.run()
                xb = b.run()
                """);
        assertEquals("A", r.receiverTypes().get("a"));
        assertEquals("B", r.receiverTypes().get("b"));
        assertEquals(List.of("int"), r.methodReturns().get("A.run"));
        assertEquals(List.of("str"), r.methodReturns().get("B.run"));
        assertEquals(List.of("int"), r.callResults().get("xa"));
        assertEquals(List.of("str"), r.callResults().get("xb"));
    }

    @Test
    void stubUsesSharedCallSiteParamHints() {
        String stub = new PythonInferencer().toStub("""
                def add(a, b):
                    return a + b

                x = add(1, 2)
                """);
        assertTrue(stub.contains("a: int") || stub.contains("def add(a: int"),
                () -> stub);
        assertTrue(stub.contains("b: int") || stub.contains(", b: int"),
                () -> stub);
    }

    @Test
    void inferStubAndSitesShareRefinedResult() throws Exception {
        Path py = tmp.resolve("shared.py");
        Files.writeString(py, """
                def f(n):
                    return n

                y = f(3)
                d = {'z': 9}
                """);
        PythonInferencer.InferResult inferred = new PythonInferencer().inferFileDetailed(py.toFile());
        assertNotNull(inferred.inference());
        assertEquals(List.of("int"), inferred.inference().refinedParams().get("f").get("n"));
        assertEquals(List.of("int"), inferred.inference().containerElements().get("d['z']"));

        List<Map<String, Object>> sites =
                new TypeEvalPySiteExporter().collect(inferred.inference());
        assertTrue(sites.stream().anyMatch(s -> "d['z']".equals(s.get("variable"))),
                () -> String.valueOf(sites));
        Map<String, Object> fp = sites.stream()
                .filter(s -> "n".equals(s.get("parameter")))
                .findFirst()
                .orElseThrow();
        assertTrue(((List<?>) fp.get("type")).contains("int"), () -> String.valueOf(fp));
    }
}
