package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;

import java.util.Map;

/**
 * Handles {@code Await}: unwrap {@code await expr} to {@code expr} for typing.
 *
 * <p>Meridian does not model awaitables separately yet; the awaited expression's
 * type is what callers (including {@code return await ...}) need. Leaving Await
 * as a no-op made {@link ReturnConverter} receive null and crash.
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
