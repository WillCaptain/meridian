package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.accessor.MemberAccessor;

import java.util.Map;

/** Handles {@code Attribute}: {@code obj.attr} → GCP {@link MemberAccessor}. */
public class AttributeConverter extends PyConverter {

    public AttributeConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Expression obj = (Expression) dispatch(ast, mapOf(pyNode, "value"));
        String attr = strOf(pyNode, "attr");
        if (obj == null || attr == null) return null;
        return new MemberAccessor(obj, identifier(ast, attr));
    }
}
