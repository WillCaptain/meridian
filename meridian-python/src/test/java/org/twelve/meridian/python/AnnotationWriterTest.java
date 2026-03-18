package org.twelve.meridian.python;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.twelve.gcp.ast.AST;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PythonAnnotationWriter} and the mypyc pipeline.
 */
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
        String code =
            "def apply_and_sum(fn, lst, n):\n" +
            "    total = 0.0\n" +
            "    for i in range(n):\n" +
            "        total += fn(lst[i])\n" +
            "    return total\n" +
            "\n" +
            "def apply_and_count_positive(fn, lst, n):\n" +
            "    count = 0\n" +
            "    for i in range(n):\n" +
            "        if fn(lst[i]) > 0.0:\n" +
            "            count += 1\n" +
            "    return count\n";
        String ctx =
            "data = [1.0, 4.0, 9.0, 16.0, 25.0]\n" +
            "r1 = apply_and_sum(lambda x: x * 2.0, data, 5)\n" +
            "r3 = apply_and_count_positive(lambda x: x - 5.0, data, 5)\n";

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
