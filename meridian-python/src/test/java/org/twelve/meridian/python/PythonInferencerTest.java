package org.twelve.meridian.python;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.twelve.gcp.ast.AST;
import org.twelve.gcp.node.expression.body.ProgramBody;
import org.twelve.gcp.node.function.FunctionNode;
import org.twelve.gcp.node.statement.Statement;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the full meridian-python pipeline.
 *
 * <p>These tests exercise the complete flow:
 * Python source → JSON AST (subprocess) → GCP AST → type inference → .pyi generation.
 */
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
}
