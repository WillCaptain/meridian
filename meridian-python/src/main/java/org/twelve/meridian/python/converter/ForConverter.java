package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.VariableKind;
import org.twelve.gcp.node.expression.Expression;
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
 *   <li>Tuple target {@code for a, b in pairs} → declares each element individually.</li>
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
            // for a, b in pairs:  each element declared via ArrayAccessor on element[0]
            List<Map<String, Object>> elts = listOf(target, "elts");
            Expression iterExpr = (Expression) dispatch(ast, iter);
            if (iterExpr == null || elts.isEmpty()) return;

            for (int idx = 0; idx < elts.size(); idx++) {
                Map<String, Object> elt = elts.get(idx);
                if (!"Name".equals(typeOf(elt))) continue;
                String varName = strOf(elt, "id");
                if (varName == null || "_".equals(varName)) continue;

                // element = pairs[0][idx]  — tells GCP the positional element type
                Expression firstElem = new org.twelve.gcp.node.expression.accessor.ArrayAccessor(
                        ast, iterExpr,
                        org.twelve.gcp.node.expression.LiteralNode.parse(ast,
                                new org.twelve.gcp.ast.Token<>(0L, 0)));
                Expression elemAtIdx = new org.twelve.gcp.node.expression.accessor.ArrayAccessor(
                        ast, firstElem,
                        org.twelve.gcp.node.expression.LiteralNode.parse(ast,
                                new org.twelve.gcp.ast.Token<>((long) idx, 0)));

                VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
                decl.declare(identifier(ast, varName), elemAtIdx);
                addStatement(ast, parent, decl);
            }
        }
    }
}
