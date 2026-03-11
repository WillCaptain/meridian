package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.statement.ExpressionStatement;

import java.util.Map;

/** Handles {@code Expr}: an expression used as a statement (e.g. a bare function call). */
public class ExprStatementConverter extends PyConverter {

    public ExprStatementConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Map<String, Object> valueNode = mapOf(pyNode, "value");
        if (valueNode == null) return null;
        // Pass parent so that Yield/YieldFrom converters can add ReturnStatement directly.
        Expression expr = (Expression) dispatch(ast, valueNode, parent);
        if (expr == null) return null;
        ExpressionStatement stmt = new ExpressionStatement(expr);
        addStatement(ast, parent, stmt);
        return stmt;
    }
}
