package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.inference.operator.BinaryOperator;
import org.twelve.gcp.node.expression.BinaryExpression;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.accessor.MemberAccessor;
import org.twelve.gcp.node.function.FunctionCallNode;
import org.twelve.gcp.node.operator.OperatorNode;

import java.util.Map;

/** Handles {@code BinOp}: arithmetic and bitwise binary expressions. */
public class BinOpConverter extends PyConverter {

    public BinOpConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        // Pass parent so NamedExpr (:=) inside binary expressions can declare variables correctly
        Expression left = (Expression) dispatch(ast, mapOf(pyNode, "left"), parent);
        Expression right = (Expression) dispatch(ast, mapOf(pyNode, "right"), parent);
        if (left == null || right == null) return null;

        String opType = typeOf(mapOf(pyNode, "op"));
        // Python 3.9+ dict literal union only: {@code {'a': 1} | d2} → merge.
        // Named {@code d1 | d2} and {@code int | int} use generic {@code BITWISE_OR}
        // until receiver-sensitive operator dispatch lands in GCP.
        if ("BitOr".equals(opType)
                && (isDictLiteral(mapOf(pyNode, "left"))
                || isDictLiteral(mapOf(pyNode, "right")))) {
            Expression merge = new MemberAccessor(left, identifier(ast, "merge"));
            return new FunctionCallNode(merge, right);
        }

        BinaryOperator op = pyArithOpToGcp(opType);
        if (op == null) return null;
        return new BinaryExpression(left, right, new OperatorNode<>(ast, op));
    }
}
