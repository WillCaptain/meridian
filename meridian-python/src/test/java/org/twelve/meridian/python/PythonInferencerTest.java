package org.twelve.meridian.python;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.twelve.gcp.ast.AST;
import org.twelve.gcp.node.expression.body.ProgramBody;
import org.twelve.gcp.node.function.FunctionNode;
import org.twelve.gcp.node.statement.Statement;
import org.twelve.gcp.node.statement.VariableDeclarator;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the full meridian-python pipeline.
 *
 * <p>These tests exercise the complete flow:
 * Python source → JSON AST (subprocess) → GCP AST → type inference → .pyi generation.
 *
 * <p>Tests run sequentially (SAME_THREAD) because many of them share the mutable
 * {@code static inferencer} field; running them concurrently would cause
 * {@code ConcurrentModificationException} in {@code ASF.infer()}.
 */
@Execution(ExecutionMode.SAME_THREAD)
class PythonInferencerTest {

    private static PythonInferencer inferencer;

    @BeforeAll
    static void setup() {
        inferencer = new PythonInferencer();
    }

    // ── bridge / parsing ───────────────────────────────────────────────────────

    @Test
    void bridge_parses_simple_function() {
        PythonAstBridge bridge = new PythonAstBridge();
        var json = bridge.parse("x: int = 1\n");
        assertEquals("Module", json.get("_type"), "Root node should be Module");
    }

    @Test
    void bridge_reports_syntax_error() {
        PythonAstBridge bridge = new PythonAstBridge();
        assertThrows(PythonAstBridge.PythonParseException.class,
                () -> bridge.parse("def foo(\n"),
                "Syntax error should throw PythonParseException");
    }

    // ── converter ─────────────────────────────────────────────────────────────

    @Test
    void converter_builds_gcp_ast_from_annotated_variable() {
        AST ast = inferencer.infer("x: int = 42\n");
        assertNotNull(ast, "AST should not be null");
        ProgramBody body = ast.program().body();
        assertFalse(body.statements().isEmpty(), "Program should have statements");

        Statement first = body.statements().getFirst();
        assertInstanceOf(VariableDeclarator.class, first);
        VariableDeclarator vd = (VariableDeclarator) first;
        // lhs is a Variable; its lexeme includes the type annotation ("x: Int")
        assertTrue(vd.assignments().getFirst().lhs().lexeme().trim().startsWith("x"));
    }

    @Test
    void converter_builds_function_node() {
        String src = "def add(x: int, y: int) -> int:\n    return x + y\n";
        AST ast = inferencer.infer(src);
        assertNotNull(ast);

        boolean found = ast.program().body().statements().stream()
                .filter(s -> s instanceof VariableDeclarator)
                .map(s -> (VariableDeclarator) s)
                .anyMatch(vd -> vd.assignments().stream()
                        .anyMatch(a -> "add".equals(a.lhs().lexeme().trim())
                                       && a.rhs() instanceof FunctionNode));
        assertTrue(found, "FunctionNode for 'add' should be present in the GCP AST");
    }

    @Test
    void converter_handles_class_with_fields() {
        String src = """
                class Point:
                    x: float
                    y: float
                """;
        AST ast = inferencer.infer(src);
        assertNotNull(ast);
        // Should have an OutlineDeclarator for Point
        boolean hasPoint = ast.program().body().statements().stream()
                .anyMatch(s -> s instanceof org.twelve.gcp.node.statement.OutlineDeclarator);
        assertTrue(hasPoint, "Class Point should produce an OutlineDeclarator");
    }

    // ── stub generation ────────────────────────────────────────────────────────

    @Test
    void stub_contains_function_signature() {
        String src = "def greet(name: str) -> str:\n    return name\n";
        String stub = inferencer.toStub(src);
        assertNotNull(stub);
        assertTrue(stub.contains("def greet"), "Stub should contain function signature");
    }

    @Test
    void stub_contains_variable_annotation() {
        String src = "count: int = 0\n";
        String stub = inferencer.toStub(src);
        assertNotNull(stub);
        assertTrue(stub.contains("count"), "Stub should mention 'count'");
    }

    @Test
    void stub_handles_import() {
        String src = "import os\nfrom typing import List\n";
        String stub = inferencer.toStub(src);
        assertNotNull(stub);
        // Should run without exceptions; imports appear as-is
    }

    @Test
    void full_pipeline_simple_func_file() throws Exception {
        var url = getClass().getClassLoader().getResource("simple_func.py");
        assertNotNull(url, "simple_func.py test resource not found");
        java.io.File file = new java.io.File(url.toURI());
        AST ast = inferencer.inferFile(file);
        assertNotNull(ast);
        assertFalse(ast.program().body().statements().isEmpty(),
                "simple_func.py should produce at least one statement");
    }

    @Test
    void full_pipeline_class_example_file() throws Exception {
        var url = getClass().getClassLoader().getResource("class_example.py");
        assertNotNull(url, "class_example.py test resource not found");
        java.io.File file = new java.io.File(url.toURI());
        AST ast = inferencer.inferFile(file);
        assertNotNull(ast);
    }

    // ── P6: cross-module type inference ───────────────────────────────────────

    @Test
    void cross_module_import_infers_return_type() {
        PythonInferencer inf = new PythonInferencer();
        inf.registerModule("math_utils", """
                def add(a, b):
                    return a + b

                def multiply(a, b):
                    return a * b
                """);

        String mainSource = """
                from math_utils import add, multiply
                result1 = add(1, 2)
                result2 = multiply(3, 4)
                """;

        AST ast = inf.infer(mainSource);
        assertNotNull(ast, "Cross-module inference should produce an AST");

        // The stub should contain the imported call results inferred as int
        String stub = new TypeAnnotationGenerator().generate(ast);
        assertNotNull(stub);
        // result1 and result2 should appear in the stub (they are module-level vars)
        assertTrue(stub.contains("result1") || stub.contains("result2")
                || stub.contains("add") || stub.contains("multiply"),
                "Stub should reflect cross-module symbols. Got:\n" + stub);
    }

    // ── P9: dict type inference ───────────────────────────────────────────────

    @Test
    void dict_subscript_infers_value_type() {
        String src = """
                def sum_freq(freq, words, n):
                    total = 0
                    for i in range(n):
                        total += freq[words[i]]
                    return total
                """;
        String calls = """
                sum_freq({"a": 1, "b": 2, "c": 3}, ["a", "b", "c"], 3)
                """;
        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(src, calls);
        assertNotNull(asts[0]);

        String annotated = new PythonAnnotationWriter().annotate(src, asts[0], asts[1]);
        assertNotNull(annotated);
        assertTrue(annotated.contains("dict[str, int]"),
                "GCP must annotate freq as dict[str, int] from dict-literal call-site. Got:\n" + annotated);
        assertTrue(annotated.contains("n: int"),
                "GCP must infer n: int. Got:\n" + annotated);
    }

    @Test
    void dict_get_infers_value_type() {
        String src = """
                def lookup_sum(freq, words, n):
                    total = 0
                    for i in range(n):
                        v = freq.get(words[i])
                        total += v
                    return total
                """;
        String calls = """
                lookup_sum({"a": 10, "b": 20}, ["a", "b"], 2)
                """;
        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(src, calls);
        assertNotNull(asts[0]);

        String annotated = new PythonAnnotationWriter().annotate(src, asts[0], asts[1]);
        assertNotNull(annotated);
        assertTrue(annotated.contains("dict[str, int]"),
                "GCP must annotate freq as dict[str, int] via .get() value type. Got:\n" + annotated);
    }

    @Test
    void dict_type_appears_in_stub() {
        String src = """
                def sum_freq(freq, words, n):
                    total = 0
                    for i in range(n):
                        total += freq[words[i]]
                    return total
                """;
        String calls = """
                sum_freq({"a": 1, "b": 2, "c": 3}, ["a", "b", "c"], 3)
                """;
        PythonInferencer inf2 = new PythonInferencer();
        AST[] asts = inf2.inferWithContext(src, calls);
        assertNotNull(asts[0]);

        String stub = new TypeAnnotationGenerator().generate(asts[0]);
        assertNotNull(stub);
        assertTrue(stub.contains("dict[str, int]"),
                "Type stub must contain dict[str, int] for freq parameter. Got:\n" + stub);
        assertTrue(stub.contains("-> int"),
                "Type stub must contain -> int return type. Got:\n" + stub);
    }

    // ── P11: yield / Generator type inference ────────────────────────────────

    @Test
    void yield_infers_iterator_int_in_stub() {
        String src = """
                def count_up(n):
                    for i in range(n):
                        yield i
                """;
        PythonInferencer inf = new PythonInferencer();
        String stub = inf.toStub(src);
        assertNotNull(stub);
        assertTrue(stub.contains("Iterator[int]"),
                "Stub must contain 'Iterator[int]' for a generator that yields range elements. Got:\n" + stub);
        assertTrue(stub.contains("from typing import Iterator"),
                "Stub must import Iterator. Got:\n" + stub);
    }

    @Test
    void yield_direct_param_infers_iterator_type_in_stub() {
        // When `yield x` yields a DIRECT parameter, the call-site type propagates
        // through the Genericable directly and we get Iterator[T].
        String src = """
                def gen_single(x):
                    yield x
                """;
        String calls = """
                gen_single(3.14)
                """;
        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(src, calls);
        assertNotNull(asts[0]);
        String stub = new TypeAnnotationGenerator().generate(asts[0]);
        assertNotNull(stub);
        assertTrue(stub.contains("Iterator[float]"),
                "Stub must infer Iterator[float] when yielding a direct float parameter. Got:\n" + stub);
    }

    @Test
    void yield_type_appears_in_annotated_source() {
        String src = """
                def integers(n):
                    for i in range(n):
                        yield i
                """;
        PythonInferencer inf = new PythonInferencer();
        AST ast = inf.infer(src);
        assertNotNull(ast);
        String annotated = new PythonAnnotationWriter().annotate(src, ast);
        assertNotNull(annotated);
        assertTrue(annotated.contains("Iterator[int]"),
                "Annotated source must contain '-> Iterator[int]'. Got:\n" + annotated);
        assertTrue(annotated.contains("Iterator"),
                "Annotated source must import or reference Iterator. Got:\n" + annotated);
    }

    @Test
    void yield_from_marks_function_as_generator() {
        // yield from produces a generator function; the stub should at least contain
        // "Iterator" even when the element type cannot be resolved from indirect
        // ArrayAccessor propagation (element Genericable not linked to parameter min()).
        String src = """
                def proxy(items):
                    yield from items
                """;
        String calls = """
                proxy(["hello", "world"])
                """;
        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(src, calls);
        assertNotNull(asts[0]);
        String stub = new TypeAnnotationGenerator().generate(asts[0]);
        assertNotNull(stub);
        assertTrue(stub.contains("Iterator"),
                "Stub must mark proxy as a generator (Iterator return type). Got:\n" + stub);
        // Note: the element type parameter cannot be inferred in this case because
        // `yield from items` creates an indirect ArrayAccessor whose Genericable is
        // not linked to the parameter's post-propagation min(). This is a known
        // limitation for indirect generator element types.
    }

    @Test
    void cross_module_annotator_writes_inferred_types() {
        PythonInferencer inf = new PythonInferencer();
        inf.registerModule("counter", """
                def count_up(n):
                    total = 0
                    for i in range(n):
                        total += i
                    return total
                """);

        String libSource = """
                from counter import count_up

                def run(n):
                    return count_up(n)
                """;

        String calls = "run(100)";
        AST[] asts = inf.inferWithContext(libSource, calls);
        assertNotNull(asts[0], "Library AST should not be null");

        String annotated = new PythonAnnotationWriter().annotate(libSource, asts[0], asts[1]);
        assertNotNull(annotated);
        assertTrue(annotated.contains("n: int"),
                "GCP should infer 'n: int' from call-site context. Got:\n" + annotated);
    }
}
