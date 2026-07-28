package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;

import java.util.Map;

/**
 * Handles {@code Await}: unwraps {@code await expr} to {@code expr} for typing.
 *
 * <p>GCP has no await node; the awaited expression's result type is what callers
 * (including {@code return await ...}) need. Leaving Await as NoOp made
 * {@link ReturnConverter} receive null and NPE inside {@code ReturnStatement}.
 */
public class AwaitConverter extends PyConverter {

    public AwaitConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        return dispatch(ast, mapOf(pyNode, "value"), parent);
    }
}
