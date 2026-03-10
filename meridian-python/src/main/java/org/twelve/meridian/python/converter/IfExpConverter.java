package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.conditions.Consequence;
import org.twelve.gcp.node.expression.conditions.IfArm;
import org.twelve.gcp.node.expression.conditions.TernaryExpression;
import org.twelve.gcp.node.statement.ReturnStatement;

import java.util.Map;

/**
 * P0 — Handles {@code IfExp}: {@code value_if_true if test else value_if_false}.
 *
 * <p>Previously only the {@code body} branch was returned, discarding the {@code orelse}
 * branch entirely and preventing GCP from inferring the union type.
 * Now produces a proper {@link TernaryExpression} so GCP can infer both branches
 * and merge their types (e.g. {@code int | float}).
 */
public class IfExpConverter extends PyConverter {

    public IfExpConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Expression test = (Expression) dispatch(ast, mapOf(pyNode, "test"));
        Expression body = (Expression) dispatch(ast, mapOf(pyNode, "body"));
        Expression orelse = (Expression) dispatch(ast, mapOf(pyNode, "orelse"));
        if (test == null || body == null || orelse == null) return body;

        TernaryExpression ternary = new TernaryExpression(ast);
        ternary.addArm(new IfArm(test, wrap(ast, body)));
        ternary.addArm(new IfArm(wrap(ast, orelse)));
        return ternary;
    }

    private static Consequence wrap(AST ast, Expression expr) {
        Consequence c = new Consequence(ast);
        c.addStatement(new ReturnStatement(expr));
        return c;
    }
}
