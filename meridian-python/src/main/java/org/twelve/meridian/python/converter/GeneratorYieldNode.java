package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.node.expression.ArrayNode;
import org.twelve.gcp.node.expression.Expression;

/**
 * Marker subclass of {@link ArrayNode} that signals the enclosing function is a Python
 * generator.  GCP infers its type identically to a regular {@link ArrayNode} (i.e.
 * {@code Array<T>}).  {@link org.twelve.meridian.python.TypeAnnotationGenerator} uses
 * {@code instanceof} detection on this marker to emit {@code Iterator[T]} instead of
 * {@code list[T]} as the function return-type annotation.
 */
public class GeneratorYieldNode extends ArrayNode {

    public GeneratorYieldNode(AST ast, Expression[] yieldedValues) {
        super(ast, yieldedValues);
    }
}
