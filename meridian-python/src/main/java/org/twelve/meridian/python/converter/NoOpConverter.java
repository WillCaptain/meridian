package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;

import java.util.Map;

/**
 * No-op converter for Python nodes that carry no type-relevant information:
 * {@code Pass}, {@code Delete}, {@code Global}, {@code Nonlocal}.
 */
public class NoOpConverter extends PyConverter {

    public NoOpConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        return null;
    }
}
