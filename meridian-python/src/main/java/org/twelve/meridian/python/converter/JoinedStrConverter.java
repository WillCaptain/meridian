package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.ast.Token;
import org.twelve.gcp.node.expression.LiteralNode;

import java.util.Map;

/**
 * P4-A — Handles {@code JoinedStr} (f-strings, e.g. {@code f"value={x}"}).
 *
 * <p>Returns a {@code str} literal so that any variable assigned an f-string
 * expression is correctly inferred as {@code str} by the GCP type engine,
 * allowing {@code mypyc} to emit optimised string-typed bytecode.
 */
public class JoinedStrConverter extends PyConverter {

    public JoinedStrConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        return LiteralNode.parse(ast, new Token<>("", 0));
    }
}
