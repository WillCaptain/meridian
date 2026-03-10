package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.ast.Token;
import org.twelve.gcp.node.expression.ArrayNode;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.LiteralNode;
import org.twelve.gcp.node.expression.accessor.ArrayAccessor;
import org.twelve.gcp.node.function.FunctionCallNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles {@code Call}: function call expression.
 *
 * <p>For well-known Python built-in functions the return type is inferred
 * directly from the arguments (P4-B), avoiding an opaque {@link FunctionCallNode}
 * that GCP cannot reason about.  Keyword arguments and starred args are skipped
 * for user-defined calls.
 */
public class CallConverter extends PyConverter {

    public CallConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Expression callee = (Expression) dispatch(ast, mapOf(pyNode, "func"));
        if (callee == null) return null;

        // P4-B: built-in function return-type inference
        Map<String, Object> funcNode = mapOf(pyNode, "func");
        if ("Name".equals(typeOf(funcNode))) {
            String funcName = strOf(funcNode, "id");
            Node builtin = tryBuiltin(ast, funcName, pyNode);
            if (builtin != null) return builtin;
        }

        List<Expression> argExprs = new ArrayList<>();
        for (Map<String, Object> arg : listOf(pyNode, "args")) {
            if ("Starred".equals(typeOf(arg))) continue;
            Expression e = (Expression) dispatch(ast, arg);
            if (e != null) argExprs.add(e);
        }
        return new FunctionCallNode(callee, argExprs.toArray(new Expression[0]));
    }

    // ── built-in return type dispatch ────────────────────────────────────────

    private Node tryBuiltin(AST ast, String name, Map<String, Object> pyNode) {
        List<Map<String, Object>> args = listOf(pyNode, "args");
        return switch (name) {
            // → int
            case "len", "id", "hash", "ord", "int" -> intLit(ast);

            // → float
            case "float" -> floatLit(ast);

            // → str
            case "str", "chr", "repr", "format", "hex", "bin", "oct" -> strLit(ast);

            // abs(x) → same type as x
            case "abs" -> {
                if (args.isEmpty()) yield null;
                yield (Expression) dispatch(ast, args.get(0));
            }

            // range(n) used as a value → list[int]
            case "range" -> new ArrayNode(ast, new Expression[]{ intLit(ast) });

            // sum(iterable) → element type of the iterable
            case "sum" -> {
                if (args.isEmpty()) yield null;
                yield elementOf(ast, args.get(0));
            }

            // min/max: single iterable → element type; multiple args → type of first arg
            case "min", "max" -> {
                if (args.isEmpty()) yield null;
                if (args.size() == 1) yield elementOf(ast, args.get(0));
                yield (Expression) dispatch(ast, args.get(0));
            }

            // sorted/list/reversed/tuple → same collection type as input
            case "sorted", "list", "reversed", "tuple" -> {
                if (args.isEmpty()) yield null;
                Map<String, Object> arg = args.get(0);
                if (isRangeCall(arg)) yield new ArrayNode(ast, new Expression[]{ intLit(ast) });
                yield (Expression) dispatch(ast, arg);
            }

            default -> null;
        };
    }

    /** Returns a typed expression whose GCP outline equals the element type of {@code iterNode}. */
    private Expression elementOf(AST ast, Map<String, Object> iterNode) {
        if (isRangeCall(iterNode)) return intLit(ast);
        Expression e = (Expression) dispatch(ast, iterNode);
        if (e == null) return null;
        return new ArrayAccessor(ast, e, intLit(ast));
    }

    private static Expression intLit(AST ast)   { return LiteralNode.parse(ast, new Token<>(0L,  0)); }
    private static Expression floatLit(AST ast) { return LiteralNode.parse(ast, new Token<>(0.0, 0)); }
    private static Expression strLit(AST ast)   { return LiteralNode.parse(ast, new Token<>("",  0)); }
}
