package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.accessor.ArrayAccessor;

import java.util.Map;

/**
 * P0 — Handles {@code Subscript}: {@code lst[i]}, {@code dct[key]}.
 *
 * <p>Maps to GCP {@link ArrayAccessor} which allows the inference engine to
 * propagate the element type from the collection type (e.g. {@code list[int]} → {@code int}).
 * Slice notation ({@code lst[1:3]}) is approximated by using the slice object as-is;
 * the return type defaults to the same array type.
 */
public class SubscriptConverter extends PyConverter {

    public SubscriptConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Expression array = (Expression) dispatch(ast, mapOf(pyNode, "value"));
        Map<String, Object> sliceNode = mapOf(pyNode, "slice");
        if (array == null || sliceNode == null) return array;

        Expression index = (Expression) dispatch(ast, sliceNode);
        if (index == null) return array;

        return new ArrayAccessor(ast, array, index);
    }
}
