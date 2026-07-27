package org.twelve.meridian.python;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.twelve.gcp.ast.AST;
import org.twelve.gcp.node.expression.BinaryExpression;
import org.twelve.gcp.node.function.FunctionCallNode;
import org.twelve.gcp.node.expression.body.Body;
import org.twelve.gcp.node.function.FunctionNode;
import org.twelve.gcp.node.statement.ReturnStatement;
import org.twelve.gcp.node.statement.Statement;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Meridian regression for Python {@code |} — not TypeEvalPy vocabulary.
 *
 * <p>Production assertions accept semantically correct types ({@code int},
 * {@code Number}, {@code dict}, …). TypeEvalPy {@code int} EXACT is handled only
 * by {@link TypeEvalPyTypeProjector} at export time.
 */
@Execution(ExecutionMode.SAME_THREAD)
class BitOrRegressionTest {

    private static PythonInferencer inferencer;

    @BeforeAll
    static void setup() {
        inferencer = new PythonInferencer();
    }

    @Test
    void int_bitwise_or_infers_int_not_dict_merge() {
        String src = """
                def f(a: int, b: int) -> int:
                    return a | b
                """;
        AST ast = inferencer.infer(src);
        assertFalse(containsMergeCall(ast), "int | int must not lower to dict.merge");
        String stub = inferencer.toStub(src);
        assertTrue(stub.contains("-> int"), () -> stub);
    }

    @Test
    void dict_literal_union_uses_merge() {
        String src = """
                def f():
                    merged = {'a': 1} | {'b': 2}
                    return merged
                """;
        AST ast = inferencer.infer(src);
        assertTrue(containsMergeCall(ast), "dict literal | dict literal should use merge");
    }

    @Test
    void named_dict_union_does_not_unconditionally_merge() {
        String src = """
                def f(d1, d2):
                    return d1 | d2
                """;
        AST ast = inferencer.infer(src);
        assertFalse(containsMergeCall(ast),
                "d1 | d2 without dict literals must not assume dict.merge");
    }

    @Test
    void subtract_infers_number_not_int() {
        String src = """
                def f(a, b):
                    return a - 1
                """;
        AST ast = inferencer.infer(src);
        String stub = inferencer.toStub(src);
        // Meridian: Number (or float/int union) is correct; TypeEvalPy int is adapter-only.
        assertFalse(stub.contains("-> int\n") && !stub.contains("Number") && !stub.contains("float"),
                "unconstrained a-1 should not be forced to int-only: " + stub);
    }

    @Test
    void number_projects_to_float_for_typeevalpy_not_int() {
        assertEquals(List.of("float"), TypeEvalPyTypeProjector.project("Number"));
        assertEquals(List.of("int"), TypeEvalPyTypeProjector.project("Int"));
        assertEquals(List.of("float"), TypeEvalPyTypeProjector.project("Number"));
    }

    private static boolean containsMergeCall(AST ast) {
        return statementsContainMerge(ast.program().body().statements());
    }

    private static boolean statementsContainMerge(List<Statement> statements) {
        for (Statement stmt : statements) {
            if (stmt instanceof VariableDeclarator vd) {
                for (var assignment : vd.assignments()) {
                    if (exprHasMergeCall(assignment.rhs())) {
                        return true;
                    }
                    if (assignment.rhs() instanceof FunctionNode fn
                            && bodyContainsMerge(fn.body())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean bodyContainsMerge(Body body) {
        for (org.twelve.gcp.ast.Node node : body.nodes()) {
            if (node instanceof Statement stmt && statementsContainMerge(List.of(stmt))) {
                return true;
            }
            if (node instanceof ReturnStatement ret && exprHasMergeCall(ret.expression())) {
                return true;
            }
        }
        return false;
    }

    private static boolean exprHasMergeCall(org.twelve.gcp.ast.Node node) {
        if (node instanceof FunctionCallNode call) {
            String lex = call.function().lexeme();
            if (lex != null && lex.contains("merge")) {
                return true;
            }
        }
        if (node instanceof BinaryExpression bin) {
            return exprHasMergeCall(bin.left()) || exprHasMergeCall(bin.right());
        }
        return false;
    }
}
