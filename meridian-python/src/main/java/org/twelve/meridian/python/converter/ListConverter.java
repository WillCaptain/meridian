package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.ArrayNode;
import org.twelve.gcp.node.expression.Expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Handles {@code List}: list literal → GCP {@link ArrayNode}. */
public class ListConverter extends PyConverter {

    public ListConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        List<Expression> elems = new ArrayList<>();
        for (Map<String, Object> elt : listOf(pyNode, "elts")) {
            Expression e = (Expression) dispatch(ast, elt);
            if (e != null) elems.add(e);
        }
        return new ArrayNode(ast, elems.toArray(new Expression[0]));
    }
}
