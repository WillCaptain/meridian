package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.VariableKind;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.Map;

/**
 * Handles {@code NamedExpr} (walrus operator {@code :=}), e.g. {@code x := expr}.
 *
 * <p>The walrus operator both assigns and returns the value. In GCP terms:
 * <ol>
 *   <li>Emit a {@link VariableDeclarator} for the target variable into the enclosing scope.</li>
 *   <li>Return the target {@link org.twelve.gcp.node.expression.identifier.Identifier} as the
 *       expression so downstream nodes can reference the typed variable.</li>
 * </ol>
 *
 * <p>Typical patterns:
 * <pre>{@code
 *   while (i := i + 1) <= n:   # i: int, total typed loop
 *   if (sq := i * i) < limit:  # sq: int
 *   [y for x in data if (y := f(x)) > 0]  # y: inferred from f(x)
 * }</pre>
 */
public class NamedExprConverter extends PyConverter {

    public NamedExprConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Map<String, Object> target = mapOf(pyNode, "target");
        Map<String, Object> valueNode = mapOf(pyNode, "value");
        if (target == null || valueNode == null) return null;

        String varName = strOf(target, "id");
        if (varName == null) return null;

        Expression value = (Expression) dispatch(ast, valueNode);
        if (value == null) return null;

        VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
        decl.declare(identifier(ast, varName), value);
        addStatement(ast, parent, decl);

        return identifier(ast, varName);
    }
}
