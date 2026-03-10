package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.statement.ReturnStatement;

import java.util.Map;

/** Handles {@code Return}: return statement inside a function body. */
public class ReturnConverter extends PyConverter {

    public ReturnConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Map<String, Object> valueNode = mapOf(pyNode, "value");
        Expression retVal = valueNode != null ? (Expression) dispatch(ast, valueNode) : null;
        ReturnStatement ret = new ReturnStatement(retVal);
        addStatement(ast, parent, ret);
        return ret;
    }
}
