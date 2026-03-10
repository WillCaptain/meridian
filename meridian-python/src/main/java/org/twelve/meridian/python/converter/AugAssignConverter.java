package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.VariableKind;
import org.twelve.gcp.inference.operator.BinaryOperator;
import org.twelve.gcp.node.expression.BinaryExpression;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.operator.OperatorNode;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.Map;

/**
 * P0 — Handles {@code AugAssign}: {@code x += expr}, {@code x -= expr}, etc.
 *
 * <p>Desugars to a reassignment: {@code x = x OP expr}, giving GCP enough
 * information to infer the accumulation variable's type (critical for loop patterns).
 * Currently handles {@code Name} targets; attribute targets are skipped.
 */
public class AugAssignConverter extends PyConverter {

    public AugAssignConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Map<String, Object> targetNode = mapOf(pyNode, "target");
        String opType = typeOf(mapOf(pyNode, "op"));
        Map<String, Object> valueNode = mapOf(pyNode, "value");

        if (!"Name".equals(typeOf(targetNode)) || valueNode == null) return null;

        String varName = strOf(targetNode, "id");
        BinaryOperator op = pyArithOpToGcp(opType);
        Expression right = (Expression) dispatch(ast, valueNode);

        if (varName == null || op == null || right == null) return null;

        Expression left = identifier(ast, varName);
        Expression combined = new BinaryExpression(left, right, new OperatorNode<>(ast, op));

        VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
        decl.declare(identifier(ast, varName), combined);
        addStatement(ast, parent, decl);
        return decl;
    }
}
