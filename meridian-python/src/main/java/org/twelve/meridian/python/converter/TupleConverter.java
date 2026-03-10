package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.TupleNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles {@code Tuple} in expression context (e.g. {@code return a, b}).
 *
 * <p>Produces a GCP {@link TupleNode} preserving positional type information.
 * When used as an assignment target, {@link AssignConverter} handles the tuple
 * pattern separately via {@link org.twelve.gcp.node.unpack.TupleUnpackNode}.
 */
public class TupleConverter extends PyConverter {

    public TupleConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        List<Expression> elems = new ArrayList<>();
        for (Map<String, Object> elt : listOf(pyNode, "elts")) {
            Expression e = (Expression) dispatch(ast, elt);
            if (e != null) elems.add(e);
        }
        return new TupleNode(elems.toArray(new Expression[0]));
    }
}
