package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.ast.Token;
import org.twelve.gcp.node.expression.LiteralNode;

import java.util.Map;

/** Handles {@code Constant}: integer, float, bool, string, None literals. */
public class ConstantConverter extends PyConverter {

    public ConstantConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Object val = pyNode.get("value");
        if (val == null) return LiteralNode.parse(ast, new Token<>(null, 0));
        if (val instanceof Integer) return LiteralNode.parse(ast, new Token<>((long)(int)(Integer) val, 0));
        if (val instanceof Long) return LiteralNode.parse(ast, new Token<>((Long) val, 0));
        if (val instanceof Double) return LiteralNode.parse(ast, new Token<>((Double) val, 0));
        if (val instanceof Float) return LiteralNode.parse(ast, new Token<>((double)(float)(Float) val, 0));
        if (val instanceof Boolean) return LiteralNode.parse(ast, new Token<>((Boolean) val, 0));
        return LiteralNode.parse(ast, new Token<>(val.toString(), 0));
    }
}
