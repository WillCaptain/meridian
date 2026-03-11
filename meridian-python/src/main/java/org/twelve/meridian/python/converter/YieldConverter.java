package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.statement.ReturnStatement;

import java.util.Map;

/**
 * Handles {@code Yield}: {@code yield expr} →
 * {@link ReturnStatement}({@link GeneratorYieldNode}([expr])).
 *
 * <p>Models the "produces one element" semantics: each {@code yield} expression is
 * treated as a return of a single-element array so GCP can infer the element type
 * (i.e. the generator's {@code Iterator[T]} type parameter).
 *
 * <p>Returns {@code null} so that the caller ({@link ExprStatementConverter}) does
 * not also wrap the result in an {@link org.twelve.gcp.node.statement.ExpressionStatement}.
 */
public class YieldConverter extends PyConverter {

    public YieldConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Map<String, Object> valueNode = mapOf(pyNode, "value");
        Expression yieldedExpr = valueNode != null ? (Expression) dispatch(ast, valueNode) : null;
        Expression[] elements = yieldedExpr != null
                ? new Expression[]{yieldedExpr}
                : new Expression[0];
        GeneratorYieldNode generatorNode = new GeneratorYieldNode(ast, elements);
        addStatement(ast, parent, new ReturnStatement(generatorNode));
        return null;
    }
}
