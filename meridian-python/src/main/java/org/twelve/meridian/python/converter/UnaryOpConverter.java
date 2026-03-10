package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.inference.operator.UnaryOperator;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.UnaryExpression;
import org.twelve.gcp.node.expression.UnaryPosition;
import org.twelve.gcp.node.operator.OperatorNode;

import java.util.Map;

/** Handles {@code UnaryOp}: {@code -x}, {@code not x}. */
public class UnaryOpConverter extends PyConverter {

    public UnaryOpConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        UnaryOperator op = pyUnaryOpToGcp(typeOf(mapOf(pyNode, "op")));
        Expression operand = (Expression) dispatch(ast, mapOf(pyNode, "operand"));
        if (operand == null) return null;
        if (op == null) return operand;
        return new UnaryExpression(operand, new OperatorNode<>(ast, op), UnaryPosition.PREFIX);
    }
}
