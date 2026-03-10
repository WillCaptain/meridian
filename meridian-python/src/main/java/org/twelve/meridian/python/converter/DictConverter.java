package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.Pair;
import org.twelve.gcp.node.expression.DictNode;
import org.twelve.gcp.node.expression.Expression;

import java.util.List;
import java.util.Map;

/** Handles {@code Dict}: {@code {k: v, ...}} literal. */
public class DictConverter extends PyConverter {

    public DictConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        List<Map<String, Object>> keys = listOf(pyNode, "keys");
        List<Map<String, Object>> values = listOf(pyNode, "values");
        int size = Math.min(keys.size(), values.size());
        Pair<Expression, Expression>[] pairs = new Pair[size];
        for (int i = 0; i < size; i++) {
            Expression k = (Expression) dispatch(ast, keys.get(i));
            Expression v = (Expression) dispatch(ast, values.get(i));
            if (k != null && v != null) pairs[i] = new Pair<>(k, v);
        }
        return new DictNode(ast, pairs);
    }
}
