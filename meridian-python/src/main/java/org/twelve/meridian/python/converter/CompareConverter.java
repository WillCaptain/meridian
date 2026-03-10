package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.inference.operator.BinaryOperator;
import org.twelve.gcp.node.expression.BinaryExpression;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.operator.OperatorNode;

import java.util.List;
import java.util.Map;

/** Handles {@code Compare}: {@code a < b}, {@code a == b}, chained comparisons. */
public class CompareConverter extends PyConverter {

    public CompareConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        // Pass parent so NamedExpr (:=) inside conditions can declare variables in the right scope
        Expression left = (Expression) dispatch(ast, mapOf(pyNode, "left"), parent);
        List<Map<String, Object>> ops = listOf(pyNode, "ops");
        List<Map<String, Object>> comparators = listOf(pyNode, "comparators");
        if (left == null || ops.isEmpty()) return null;

        Expression result = left;
        for (int i = 0; i < ops.size(); i++) {
            BinaryOperator op = pyCmpOpToGcp(typeOf(ops.get(i)));
            Expression right = (Expression) dispatch(ast, comparators.get(i), parent);
            if (op != null && right != null) {
                result = new BinaryExpression(result, right, new OperatorNode<>(ast, op));
            }
        }
        return result;
    }
}
