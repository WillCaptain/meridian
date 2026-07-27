package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.Pair;
import org.twelve.gcp.node.expression.DictNode;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.accessor.MemberAccessor;
import org.twelve.gcp.node.function.FunctionCallNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles {@code Dict}: {@code {k: v, ...}} and dict unpack {@code {**a, **b}}.
 */
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

        List<Pair<Expression, Expression>> pairs = new ArrayList<>();
        List<Expression> unpacked = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            Map<String, Object> keyNode = keys.get(i);
            // Python represents {**d} as key=null, value=d
            if (keyNode == null) {
                Expression u = (Expression) dispatch(ast, values.get(i));
                if (u != null) unpacked.add(u);
                continue;
            }
            Expression k = (Expression) dispatch(ast, keyNode);
            Expression v = (Expression) dispatch(ast, values.get(i));
            if (k != null && v != null) pairs.add(new Pair<>(k, v));
        }

        DictNode literal = new DictNode(ast, pairs.toArray(new Pair[0]));
        if (unpacked.isEmpty()) return literal;

        // {**a, **b} / {k:v, **a} → successive dict.merge (GCP immutable union)
        Expression acc;
        int i0;
        if (pairs.isEmpty()) {
            acc = unpacked.get(0);
            i0 = 1;
        } else {
            acc = literal;
            i0 = 0;
        }
        for (int i = i0; i < unpacked.size(); i++) {
            Expression merge = new MemberAccessor(acc, identifier(ast, "merge"));
            acc = new FunctionCallNode(merge, unpacked.get(i));
        }
        return acc;
    }
}
