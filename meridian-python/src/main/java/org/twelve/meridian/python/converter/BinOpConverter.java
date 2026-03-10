package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.inference.operator.BinaryOperator;
import org.twelve.gcp.node.expression.BinaryExpression;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.operator.OperatorNode;

import java.util.Map;

/** Handles {@code BinOp}: arithmetic and bitwise binary expressions. */
public class BinOpConverter extends PyConverter {

    public BinOpConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Expression left = (Expression) dispatch(ast, mapOf(pyNode, "left"));
        Expression right = (Expression) dispatch(ast, mapOf(pyNode, "right"));
        BinaryOperator op = pyArithOpToGcp(typeOf(mapOf(pyNode, "op")));
        if (left == null || right == null || op == null) return null;
        return new BinaryExpression(left, right, new OperatorNode<>(ast, op));
    }
}
