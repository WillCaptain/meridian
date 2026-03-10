package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.VariableKind;
import org.twelve.gcp.node.expression.ArrayNode;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.List;
import java.util.Map;

/**
 * Handles {@code ListComp}: {@code [elt for target in iter if ...]}
 *
 * <p>GCP doesn't have a native comprehension node. This converter produces an
 * {@link ArrayNode} whose single element is the element expression ({@code elt}),
 * giving GCP enough type information to infer {@code Array<T>} for the result.
 *
 * <p>The comprehension variable ({@code target}) is declared in the parent scope
 * with the element type of {@code iter} so that subsequent expressions (e.g. the
 * element body) can reference it with a concrete type.
 *
 * <p>Also handles {@code SetComp} and {@code GeneratorExp} with the same logic;
 * these are registered in {@link org.twelve.meridian.python.PythonGCPConverter}.
 */
public class ListCompConverter extends PyConverter {

    public ListCompConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        List<Map<String, Object>> generators = listOf(pyNode, "generators");
        if (generators.isEmpty()) return null;

        Map<String, Object> gen    = generators.get(0);
        Map<String, Object> target = mapOf(gen, "target");
        Map<String, Object> iter   = mapOf(gen, "iter");
        Map<String, Object> elt    = mapOf(pyNode, "elt");

        // Declare the comprehension variable in the enclosing scope
        // so GCP can see its type when evaluating the element expression.
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

        // Build ArrayNode([elt]) — one representative element gives GCP the element type.
        Expression eltExpr = (Expression) dispatch(ast, elt);
        if (eltExpr == null) return null;
        return new ArrayNode(ast, new Expression[]{eltExpr});
    }
}
