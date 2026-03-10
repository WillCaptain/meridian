package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.function.FunctionCallNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Handles {@code Call}: function call expression. Keyword arguments and starred args are skipped. */
public class CallConverter extends PyConverter {

    public CallConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Expression callee = (Expression) dispatch(ast, mapOf(pyNode, "func"));
        if (callee == null) return null;

        List<Expression> argExprs = new ArrayList<>();
        for (Map<String, Object> arg : listOf(pyNode, "args")) {
            if ("Starred".equals(typeOf(arg))) continue;
            Expression e = (Expression) dispatch(ast, arg);
            if (e != null) argExprs.add(e);
        }
        return new FunctionCallNode(callee, argExprs.toArray(new Expression[0]));
    }
}
