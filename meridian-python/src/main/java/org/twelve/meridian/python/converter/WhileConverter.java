package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;

import java.util.Map;

/** Handles {@code While} loop: dispatches the body for return/assign inference. */
public class WhileConverter extends PyConverter {

    public WhileConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        for (Map<String, Object> stmt : listOf(pyNode, "body")) dispatch(ast, stmt, parent);
        return null;
    }
}
