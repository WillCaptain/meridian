package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Token;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.VariableKind;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.LiteralNode;
import org.twelve.gcp.node.expression.accessor.ArrayAccessor;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.List;
import java.util.Map;

/**
 * Handles {@code For} loop.
 *
 * <p>Declares the loop target variable before dispatching body statements so that
 * GCP can infer its type from the iterable:
 * <ul>
 *   <li>{@code for i in range(n)} → declares {@code i = 0} (integer).</li>
 *   <li>{@code for x in some_list} → declares {@code x = some_list[0]} (element type).</li>
 *   <li>{@code for i, x in enumerate(lst)} → {@code i = 0} (int), {@code x = lst[0]} (element type).</li>
 *   <li>{@code for a, b in zip(l1, l2)} → {@code a = l1[0]}, {@code b = l2[0]} (typed per list).</li>
 *   <li>Generic tuple target {@code for a, b in pairs} → each element declared via index access.</li>
 * </ul>
 */
public class ForConverter extends PyConverter {

    public ForConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Map<String, Object> target = mapOf(pyNode, "target");
        Map<String, Object> iter   = mapOf(pyNode, "iter");

        if (target != null && iter != null) {
            declareLoopTarget(ast, parent, target, iter);
        }

        for (Map<String, Object> stmt : listOf(pyNode, "body")) {
            dispatch(ast, stmt, parent);
        }
        return null;
    }

    private void declareLoopTarget(AST ast, Node parent,
                                   Map<String, Object> target,
                                   Map<String, Object> iter) {
        String targetType = typeOf(target);

        if ("Name".equals(targetType)) {
            String varName = strOf(target, "id");
            if (varName == null || "_".equals(varName)) return;

            Expression init = buildIterElementExpr(ast, iter);
            if (init == null) return;

            VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
            decl.declare(identifier(ast, varName), init);
            addStatement(ast, parent, decl);

        } else if ("Tuple".equals(targetType)) {
            List<Map<String, Object>> elts = listOf(target, "elts");
            if (elts.isEmpty()) return;

            // ── enumerate(iterable): i = 0 (int), x = iterable[0] (element type) ──────
            if (isBuiltinCall(iter, "enumerate") && elts.size() == 2) {
                List<Map<String, Object>> args = listOf(iter, "args");
                if (!args.isEmpty()) {
                    // Index variable is always int
                    declareVar(ast, parent, strOf(elts.get(0), "id"),
                            LiteralNode.parse(ast, new Token<>(0L, 0)));
                    // Element variable: use range short-circuit or array accessor
                    declareVar(ast, parent, strOf(elts.get(1), "id"),
                            iterElementExpr(ast, args.get(0)));
                    return;
                }
            }

            // ── zip(lst1, lst2, ...): a = lst1[0], b = lst2[0], ... ──────────────────
            if (isBuiltinCall(iter, "zip")) {
                List<Map<String, Object>> args = listOf(iter, "args");
                for (int idx = 0; idx < Math.min(elts.size(), args.size()); idx++) {
                    Map<String, Object> elt = elts.get(idx);
                    if (!"Name".equals(typeOf(elt))) continue;
                    declareVar(ast, parent, strOf(elt, "id"),
                            iterElementExpr(ast, args.get(idx)));
                }
                return;
            }

            // ── generic iterable of tuples: for a, b in pairs → pairs[0][0], pairs[0][1] ──
            Expression iterExpr = (Expression) dispatch(ast, iter);
            if (iterExpr == null) return;

            for (int idx = 0; idx < elts.size(); idx++) {
                Map<String, Object> elt = elts.get(idx);
                if (!"Name".equals(typeOf(elt))) continue;
                String varName = strOf(elt, "id");
                if (varName == null || "_".equals(varName)) continue;

                Expression firstElem = new ArrayAccessor(ast, iterExpr,
                        LiteralNode.parse(ast, new Token<>(0L, 0)));
                Expression elemAtIdx = new ArrayAccessor(ast, firstElem,
                        LiteralNode.parse(ast, new Token<>((long) idx, 0)));

                declareVar(ast, parent, varName, elemAtIdx);
            }
        }
    }

    /**
     * Return an expression whose GCP outline equals the element type of the given iterable node.
     * <ul>
     *   <li>{@code range(...)} → {@code 0L} literal (always integer).</li>
     *   <li>Any other iterable → {@code iterable[0]} via {@link ArrayAccessor}.</li>
     * </ul>
     */
    private Expression iterElementExpr(AST ast, Map<String, Object> innerArg) {
        if (isRangeCall(innerArg)) {
            return LiteralNode.parse(ast, new Token<>(0L, 0));
        }
        Expression expr = (Expression) dispatch(ast, innerArg);
        if (expr == null) return null;
        return new ArrayAccessor(ast, expr, LiteralNode.parse(ast, new Token<>(0L, 0)));
    }

    private void declareVar(AST ast, Node parent, String name, Expression init) {
        if (name == null || "_".equals(name) || init == null) return;
        VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
        decl.declare(identifier(ast, name), init);
        addStatement(ast, parent, decl);
    }

    /** True when {@code iter} is a direct call to a builtin with the given name. */
    private static boolean isBuiltinCall(Map<String, Object> iter, String funcName) {
        if (!"Call".equals(typeOf(iter))) return false;
        Map<String, Object> func = mapOf(iter, "func");
        return func != null && funcName.equals(strOf(func, "id"));
    }
}
