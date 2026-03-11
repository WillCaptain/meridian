package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.ast.Token;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.LiteralNode;
import org.twelve.gcp.node.expression.accessor.ArrayAccessor;
import org.twelve.gcp.node.statement.ReturnStatement;

import java.util.Map;

/**
 * Handles {@code YieldFrom}: {@code yield from iter} →
 * {@link ReturnStatement}({@link GeneratorYieldNode}([iter[0]])).
 *
 * <p>Maps delegation to a single-element array whose element is {@code iter[0]}, so
 * GCP can infer the element type of the delegated iterable.  The resulting function
 * return annotation will be {@code Iterator[T]} where {@code T} is the element type
 * of {@code iter}.
 */
public class YieldFromConverter extends PyConverter {

    public YieldFromConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Map<String, Object> valueNode = mapOf(pyNode, "value");
        Expression iterExpr = valueNode != null ? (Expression) dispatch(ast, valueNode) : null;

        Expression element = iterExpr != null
                ? new ArrayAccessor(ast, iterExpr, LiteralNode.parse(ast, new Token<>(0L, 0)))
                : null;

        Expression[] elements = element != null ? new Expression[]{element} : new Expression[0];
        GeneratorYieldNode generatorNode = new GeneratorYieldNode(ast, elements);
        addStatement(ast, parent, new ReturnStatement(generatorNode));
        return null;
    }
}
