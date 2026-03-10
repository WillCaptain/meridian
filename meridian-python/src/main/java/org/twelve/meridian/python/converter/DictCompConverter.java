package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.VariableKind;
import org.twelve.gcp.common.Pair;
import org.twelve.gcp.node.expression.DictNode;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.List;
import java.util.Map;

/**
 * Handles {@code DictComp}: {@code {key: val for target in iter}}.
 *
 * <p>Produces a {@link DictNode} with one representative key-value pair so GCP
 * can infer the dict's key and value types.
 */
public class DictCompConverter extends PyConverter {

    public DictCompConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        List<Map<String, Object>> generators = listOf(pyNode, "generators");
        if (generators.isEmpty()) return null;

        Map<String, Object> gen    = generators.get(0);
        Map<String, Object> target = mapOf(gen, "target");
        Map<String, Object> iter   = mapOf(gen, "iter");
        Map<String, Object> keyNode = mapOf(pyNode, "key");
        Map<String, Object> valNode = mapOf(pyNode, "value");

        // Declare comprehension variable in parent scope
        if (target != null && iter != null && "Name".equals(typeOf(target))) {
            String varName = strOf(target, "id");
            if (varName != null && !"_".equals(varName)) {
                Expression initExpr = buildIterElementExpr(ast, iter);
                if (initExpr != null) {
                    VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
                    decl.declare(identifier(ast, varName), initExpr);
                    addStatement(ast, parent, decl);
                }
            }
        }

        Expression keyExpr = (Expression) dispatch(ast, keyNode);
        Expression valExpr = (Expression) dispatch(ast, valNode);
        if (keyExpr == null || valExpr == null) return null;

        @SuppressWarnings("unchecked")
        Pair<Expression, Expression>[] pairs = new Pair[]{new Pair<>(keyExpr, valExpr)};
        return new DictNode(ast, pairs);
    }
}
