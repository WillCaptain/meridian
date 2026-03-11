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
        Map<String, Object> funcNode = mapOf(pyNode, "func");

        // P5-A: method call on a receiver — must be checked BEFORE dispatching callee
        // to avoid GCP's MemberAccessorInference reporting FIELD_NOT_FOUND on primitives.
        if ("Attribute".equals(typeOf(funcNode))) {
            Node method = tryMethodCall(ast, funcNode, pyNode);
            if (method != null) return method;
        }

        Expression callee = (Expression) dispatch(ast, funcNode);
        if (callee == null) return null;

        // P4-B: built-in function return-type inference
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

    // ── P5-A: method call return-type inference ──────────────────────────────

    /**
     * Infers the return type of a method call.
     *
     * <p><b>P5-A</b>: For well-known Python built-in type methods (str, list, int, …) the return
     * type is inferred from a hard-coded name → expression table, bypassing GCP's member-access
     * inference which reports {@code FIELD_NOT_FOUND} for primitive types.
     *
     * <p><b>P7</b>: For any unrecognised method name, the call is desugared to a top-level
     * function call: {@code obj.method(args)} → {@code method(obj, args)}.  This works because
     * {@link org.twelve.meridian.python.converter.ClassDefConverter ClassDefConverter} registers
     * each class method as a module-level function, so GCP's inference can resolve the callee and
     * propagate the receiver type to the {@code self} parameter.  Methods not found in the symbol
     * table simply produce {@code UNKNOWN}, identical to the previous {@code FIELD_NOT_FOUND} path.
     *
     * @param funcNode the {@code Attribute} AST node ({@code value} = receiver, {@code attr} = method)
     * @param callNode the full {@code Call} AST node (for accessing args)
     */
    private Node tryMethodCall(AST ast, Map<String, Object> funcNode, Map<String, Object> callNode) {
        String method = strOf(funcNode, "attr");
        if (method == null) return null;

        List<Map<String, Object>> args = listOf(callNode, "args");

        return switch (method) {

            // ── always → int ──────────────────────────────────────────────────
            case "count",    // str.count(sub) / list.count(x) → int
                 "index",    // str.index(sub) / list.index(x) → int
                 "find",     // str.find(sub) → int
                 "rfind",    // str.rfind(sub) → int
                 "rindex",   // str.rindex(sub) → int
                 "bit_length"// int.bit_length() → int
                    -> intLit(ast);

            // ── always → bool ─────────────────────────────────────────────────
            case "startswith", "endswith",
                 "isdigit",  "isalpha",  "isalnum",  "isspace",
                 "isupper",  "islower",  "istitle",
                 "isdecimal","isnumeric","isidentifier",
                 "issubset", "issuperset","isdisjoint"
                    -> LiteralNode.parse(ast, new Token<>(false, 0));

            // ── always → str ──────────────────────────────────────────────────
            case "upper",   "lower",    "capitalize","title",   "swapcase",
                 "strip",   "lstrip",   "rstrip",
                 "replace",
                 "format",  "format_map",
                 "join",                             // str.join(iterable) → str
                 "zfill",   "center",   "ljust",    "rjust",
                 "expandtabs", "encode", "decode"
                    -> strLit(ast);

            // ── str split → list[str] ─────────────────────────────────────────
            case "split", "rsplit", "splitlines", "partition", "rpartition"
                    -> new ArrayNode(ast, new Expression[]{ strLit(ast) });

            // ── dict.get(key) / list.pop([i]) → element/value type of receiver ──
            // For dict: d.get(key) ≈ d[key] in type semantics → value type V.
            // For list: list.pop([i]) → element type via ArrayAccessor(recv, arg-or-0).
            case "get" -> {
                Map<String, Object> recvNode = mapOf(funcNode, "value");
                Expression recv = (Expression) dispatch(ast, recvNode);
                if (recv == null) yield null;
                Expression keyExpr = args.isEmpty() ? null
                        : (Expression) dispatch(ast, args.get(0));
                if (keyExpr == null) yield null;
                yield new ArrayAccessor(ast, recv, keyExpr);
            }

            case "pop" -> {
                Map<String, Object> recvNode = mapOf(funcNode, "value");
                Expression recv = (Expression) dispatch(ast, recvNode);
                if (recv == null) yield null;
                // dict.pop(key) uses the key arg; list.pop() uses index 0
                Expression keyExpr = args.isEmpty() ? intLit(ast)
                        : (Expression) dispatch(ast, args.get(0));
                if (keyExpr == null) keyExpr = intLit(ast);
                yield new ArrayAccessor(ast, recv, keyExpr);
            }

            // ── mutations that return None ────────────────────────────────────
            case "append", "extend",  "insert",  "remove",  "discard",
                 "add",    "clear",   "sort",    "reverse", "update",
                 "__setitem__"
                    -> LiteralNode.parse(ast, new Token<>(null, 0));

            // ── P9: dict built-in methods — pass through to GCP's Dict.loadBuiltInMethods ──
            // Returning null here bypasses P7 desugaring and lets the outer convert() method
            // produce FunctionCallNode(MemberAccessor(recv, "keys/values/items"), []), which
            // MemberAccessorInference resolves via Dict.loadBuiltInMethods().
            // keys()   → Array<K>  (all keys as list)
            // values() → Array<V>  (all values as list)
            // items()  → pass-through (GCP does not model items yet; gives UNKNOWN gracefully)
            case "keys", "values", "items", "setdefault" -> null;

            default -> {
                // P7: user-defined class method — desugar obj.method(args) → method(obj, args).
                // ClassDefConverter has already registered each class method as a module-level
                // function; calling method(receiver, args) passes 'self' as the first argument
                // so GCP can propagate the receiver type into the method body and infer the
                // return type correctly.
                Map<String, Object> recvNode = mapOf(funcNode, "value");
                Expression receiver = (Expression) dispatch(ast, recvNode);
                if (receiver == null) yield null;
                Expression callee = identifier(ast, method);
                List<Expression> allArgs = new ArrayList<>();
                allArgs.add(receiver);
                for (Map<String, Object> arg : args) {
                    if ("Starred".equals(typeOf(arg))) continue;
                    Expression e = (Expression) dispatch(ast, arg);
                    if (e != null) allArgs.add(e);
                }
                yield new FunctionCallNode(callee, allArgs.toArray(new Expression[0]));
            }
        };
    }

    private static Expression intLit(AST ast)   { return LiteralNode.parse(ast, new Token<>(0L,  0)); }
    private static Expression floatLit(AST ast) { return LiteralNode.parse(ast, new Token<>(0.0, 0)); }
    private static Expression strLit(AST ast)   { return LiteralNode.parse(ast, new Token<>("",  0)); }
}
