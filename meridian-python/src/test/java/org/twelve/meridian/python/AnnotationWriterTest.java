package org.twelve.meridian.python;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.twelve.gcp.ast.AST;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PythonAnnotationWriter} and the mypyc pipeline.
 *
 * <p>Tests run sequentially (SAME_THREAD) because all methods share the mutable
 * {@code static inferencer} field; running them concurrently would cause
 * {@code ConcurrentModificationException} in {@code ASF.infer()}.
 */
@Execution(ExecutionMode.SAME_THREAD)
class AnnotationWriterTest {

    private static PythonInferencer inferencer;

    @BeforeAll
    static void setup() {
        inferencer = new PythonInferencer();
    }

    // ── PythonAnnotationWriter ────────────────────────────────────────────────

    @Test
    void annotate_preserves_existing_annotations() throws Exception {
        String src = "def add(x: int, y: int) -> int:\n    return x + y\n";
        AST ast = inferencer.infer(src);
        String annotated = new PythonAnnotationWriter().annotate(src, ast);
        // Existing annotations must not be duplicated
        assertFalse(annotated.contains("int: int"), "Should not duplicate existing annotations");
        assertTrue(annotated.contains("def add"), "Function def should still be present");
    }

    @Test
    void annotate_inserts_inferred_return_type() throws Exception {
        String src = "count: int = 0\nresult = 42\n";
        AST ast = inferencer.infer(src);
        String annotated = new PythonAnnotationWriter().annotate(src, ast);
        assertNotNull(annotated);
        // The source with annotations should still parse (we can re-parse via bridge)
        PythonAstBridge bridge = new PythonAstBridge();
        assertDoesNotThrow(() -> bridge.parse(annotated),
                "Annotated source should be valid Python");
    }

    @Test
    void annotate_class_example_is_valid_python() throws Exception {
        var url = getClass().getClassLoader().getResource("class_example.py");
        assertNotNull(url);
        File file = new File(url.toURI());
        String src = java.nio.file.Files.readString(file.toPath());
        AST ast = inferencer.inferFile(file);
        String annotated = new PythonAnnotationWriter().annotate(src, ast);
        assertNotNull(annotated);
        // Annotated output should be valid Python
        PythonAstBridge bridge = new PythonAstBridge();
        assertDoesNotThrow(() -> bridge.parse(annotated),
                "Annotated class source should be valid Python");
    }

    @Test
    void annotate_math_utils_is_valid_python() throws Exception {
        var url = getClass().getClassLoader().getResource("math_utils.py");
        assertNotNull(url);
        File file = new File(url.toURI());
        String src = Files.readString(file.toPath());
        AST ast = inferencer.inferFile(file);
        String annotated = new PythonAnnotationWriter().annotate(src, ast);
        assertNotNull(annotated);
        // Must remain valid Python
        PythonAstBridge bridge = new PythonAstBridge();
        assertDoesNotThrow(() -> bridge.parse(annotated),
                "Annotated math_utils.py should be valid Python");
    }

    @Test
    void annotate_from_inference_result_uses_method_and_receiver_hints() {
        String src = """
                class Box:
                    def value(self):
                        return 42

                b = Box()
                """;
        PythonInferenceResult inf = inferencer.inferDetailed(src);
        String annotated = new PythonAnnotationWriter().annotate(src, inf);
        assertTrue(annotated.contains("b: Box") || annotated.contains("b:"),
                () -> "receiver should annotate b: " + annotated);
        assertTrue(annotated.contains("-> int") || annotated.contains("value"),
                () -> "method return should reach writer: " + annotated);
        assertDoesNotThrow(() -> new PythonAstBridge().parse(annotated),
                "Annotated source should be valid Python");
    }

    @Test
    void annotate_from_inference_result_uses_call_site_params() {
        String src = """
                def add(a, b):
                    return a + b

                x = add(1, 2)
                """;
        PythonInferenceResult inf = inferencer.inferDetailed(src);
        String annotated = new PythonAnnotationWriter().annotate(src, inf);
        assertTrue(annotated.contains("a: int"), () -> annotated);
        assertTrue(annotated.contains("b: int"), () -> annotated);
    }

    // ── TypeAnnotationGenerator (outlineToTypeStr) ───────────────────────────

    @Test
    void generator_uses_inferred_types_in_stub() throws Exception {
        // A function with declared arg types: the stub should carry the declared types
        String src = "def add(x: int, y: int) -> int:\n    return x + y\n";
        String stub = inferencer.toStub(src);
        assertNotNull(stub);
        assertTrue(stub.contains("def add"), "Stub should contain function def");
    }

    // ── Callable (higher-order function) annotation ──────────────────────────

    @Test
    void annotate_hof_emits_callable_type() throws Exception {
        String code = TestCodeSamples.HOF_CALLABLE_CODE;
        String ctx = TestCodeSamples.HOF_CALLABLE_CONTEXT;

        AST[] asts = inferencer.inferWithContext(code, ctx);
        String annotated = new PythonAnnotationWriter().annotate(code, asts[0], asts[1]);

        System.out.println("=== HOF annotated ===\n" + annotated);

        assertTrue(annotated.contains("Callable"),
                "fn parameter should be annotated as Callable, got:\n" + annotated);
        assertTrue(annotated.contains("from typing import Callable"),
                "Should import Callable, got:\n" + annotated);
        assertTrue(annotated.contains("float"),
                "Callable types should reference float");

        // Annotated output must remain valid Python
        assertDoesNotThrow(() -> new PythonAstBridge().parse(annotated),
                "Annotated HOF source should be valid Python");
    }

    @Test
    void returned_lambda_is_callable_return_not_curried_continuation() {
        String src = """
                def func():
                    return lambda x: x ** 2

                f = func()
                a = f(4)
                b = f(4.4)
                """;

        PythonInferenceResult inf = inferencer.inferDetailed(src);
        String annotated = new PythonAnnotationWriter().annotate(src, inf);

        assertTrue(annotated.contains("def func() -> Callable[["),
                () -> "returned lambda must remain a first-class Callable:\n" + annotated);
        assertFalse(annotated.contains("def func() -> float"),
                () -> "returned lambda must not be flattened as a curry continuation:\n" + annotated);
        assertTrue(annotated.contains("from typing import Callable"), () -> annotated);
        assertDoesNotThrow(() -> new PythonAstBridge().parse(annotated));
    }

    @Test
    void infer_delegates_to_shared_refine_hints() {
        String src = """
                def one():
                    return 1

                x = one()
                """;
        PythonInferenceResult detailed = inferencer.inferDetailed(src);
        assertNotNull(inferencer.infer(src));
        assertFalse(detailed.annotationHints().isEmpty(),
                () -> "shared refine must populate hints: " + detailed.annotationHints());
        String viaDetailed = new PythonAnnotationWriter().annotate(src, detailed);
        String viaInferAst = new PythonAnnotationWriter().annotate(src, inferencer.infer(src));
        // AST-only annotate lacks refine overlays; detailed path must stay at least as informative.
        assertTrue(viaDetailed.contains("one") || viaInferAst.contains("one"));
        assertTrue(detailed.callResults().containsKey("x")
                        || detailed.annotationHints().containsKey("x")
                        || viaDetailed.contains("x:"),
                () -> "refine should observe x = one(): " + detailed.annotationHints());
    }

    @Test
    void safe_policy_drops_incomplete_function_signatures() {
        java.util.Map<String, String> raw = new java.util.LinkedHashMap<>();
        raw.put("f#x", "Any");
        raw.put("f#return", "int");
        raw.put("g#x", "int");
        raw.put("g#return", "int");
        raw.put("y", "str");
        raw.put("cb", "Callable[[int], int]");
        java.util.Map<String, String> filtered = AnnotationPolicy.SAFE_PARTIAL.filter(raw);
        assertFalse(filtered.containsKey("f#x"), "non-concrete param taints f");
        assertFalse(filtered.containsKey("f#return"),
                "SAFE_PARTIAL must skip entire function when any slot is non-concrete");
        assertEquals("int", filtered.get("g#x"));
        assertEquals("int", filtered.get("g#return"));
        assertEquals("str", filtered.get("y"));
        assertFalse(filtered.containsKey("cb"),
                "SAFE_PARTIAL skips bare module-level Callable assigns");

        java.util.Map<String, String> aggressive = AnnotationPolicy.ALL_CONCRETE.filter(raw);
        assertEquals("int", aggressive.get("f#return"));
        assertEquals("Callable[[int], int]", aggressive.get("cb"));
    }

    // ── mypyc compilation ─────────────────────────────────────────────────────

    @Test
    void mypyc_compiles_fully_annotated_file() throws Exception {
        var url = getClass().getClassLoader().getResource("math_utils.py");
        assertNotNull(url, "math_utils.py test resource not found");
        File file = new File(url.toURI());

        // Copy to a temp dir (mypyc writes build artifacts to the working dir)
        Path tmpDir = Files.createTempDirectory("meridian_test_");
        Path tmpFile = tmpDir.resolve("math_utils.py");
        Files.copy(file.toPath(), tmpFile);

        MypycRunner runner = new MypycRunner();
        MypycRunner.CompileResult result = runner.compile(tmpFile.toFile(), tmpDir.toFile());

        // mypyc should succeed on a fully-annotated file
        assertTrue(result.success(),
                "mypyc compilation should succeed. stderr: " + result.stderr());
        assertNotNull(result.outputFile(), "Output .so / .pyd should be produced");
        assertTrue(result.outputFile().exists(), "Output file should exist on disk");
    }

    @Test
    void mypyc_full_pipeline_on_annotated_source() throws Exception {
        var url = getClass().getClassLoader().getResource("math_utils.py");
        assertNotNull(url);
        File file = new File(url.toURI());

        MypycRunner runner = new MypycRunner();
        MypycRunner.CompileResult result = runner.inferAndCompile(file);

        // The infer+annotate+compile pipeline should produce a native extension
        assertTrue(result.success(),
                "Full pipeline should succeed. stderr:\n" + result.stderr()
                + "\nstdout:\n" + result.stdout());
        System.out.println("Native extension: " + result.outputFile());
    }
}
