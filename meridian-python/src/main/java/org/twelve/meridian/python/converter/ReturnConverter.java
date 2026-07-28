package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.ast.Token;
import org.twelve.gcp.common.NullLiteral;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.LiteralNode;
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
        Expression retVal = null;
        if (valueNode != null) {
            Node converted = dispatch(ast, valueNode);
            if (converted instanceof Expression expr) {
                retVal = expr;
            }
        }
        // bare `return`, or unsupported/unconverted value → return None
        if (retVal == null) {
            retVal = LiteralNode.parse(ast, new Token<>(NullLiteral.INSTANCE, 0));
        }
        ReturnStatement ret = new ReturnStatement(retVal);
        addStatement(ast, parent, ret);
        return ret;
    }
}
