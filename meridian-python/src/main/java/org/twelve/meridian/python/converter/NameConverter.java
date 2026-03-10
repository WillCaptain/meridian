package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;

import java.util.Map;

/** Handles {@code Name}: variable reference → GCP {@link org.twelve.gcp.node.expression.identifier.Identifier}. */
public class NameConverter extends PyConverter {

    public NameConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        return identifier(ast, strOf(pyNode, "id"));
    }
}
