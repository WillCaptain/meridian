package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.inference.operator.BinaryOperator;
import org.twelve.gcp.node.expression.BinaryExpression;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.operator.OperatorNode;

import java.util.List;
import java.util.Map;

/** Handles {@code BoolOp}: {@code a and b and c} / {@code a or b or c}. Left-folds n-ary into binary. */
public class BoolOpConverter extends PyConverter {

    public BoolOpConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        String opType = typeOf(mapOf(pyNode, "op"));
        BinaryOperator op = "And".equals(opType) ? BinaryOperator.LOGICAL_AND : BinaryOperator.LOGICAL_OR;
        List<Map<String, Object>> valNodes = listOf(pyNode, "values");
        if (valNodes.isEmpty()) return null;

        Expression result = (Expression) dispatch(ast, valNodes.getFirst());
        for (int i = 1; i < valNodes.size(); i++) {
            Expression right = (Expression) dispatch(ast, valNodes.get(i));
            if (result == null || right == null) continue;
            result = new BinaryExpression(result, right, new OperatorNode<>(ast, op));
        }
        return result;
    }
}
