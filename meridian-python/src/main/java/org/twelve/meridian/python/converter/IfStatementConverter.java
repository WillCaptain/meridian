package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;

import java.util.Map;

/**
 * Handles {@code If} statement: dispatches both branches so that
 * nested returns and assignments are visible to GCP inference.
 */
public class IfStatementConverter extends PyConverter {

    public IfStatementConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        // Dispatch condition so NamedExpr (:=) in if-conditions can declare variables
        dispatch(ast, mapOf(pyNode, "test"), parent);
        for (Map<String, Object> stmt : listOf(pyNode, "body")) dispatch(ast, stmt, parent);
        for (Map<String, Object> stmt : listOf(pyNode, "orelse")) dispatch(ast, stmt, parent);
        return null;
    }
}
