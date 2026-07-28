package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.ast.Token;
import org.twelve.gcp.common.NullLiteral;
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
        // Python None arrives as JSON/bridge null — map to GCP NullLiteral (Token forbids null data).
        if (val == null) {
            return LiteralNode.parse(ast, new Token<>(NullLiteral.INSTANCE, 0));
        }
        // py_ast_dump encodes Ellipsis / other non-JSON scalars as marker maps.
        if (val instanceof Map<?, ?> map) {
            if (Boolean.TRUE.equals(map.get("_ellipsis"))
                    || "Ellipsis".equals(map.get("__meridian__"))) {
                return LiteralNode.parse(ast, new Token<>("...", 0));
            }
            if ("complex".equals(map.get("__meridian__"))) {
                double real = map.get("real") instanceof Number n ? n.doubleValue() : 0.0;
                double imag = map.get("imag") instanceof Number n ? n.doubleValue() : 0.0;
                return LiteralNode.parse(ast, new Token<>(real + "+" + imag + "j", 0));
            }
            if ("repr".equals(map.get("__meridian__"))) {
                Object repr = map.get("value");
                return LiteralNode.parse(ast, new Token<>(repr == null ? "" : repr.toString(), 0));
            }
        }
        if (val instanceof Integer) return LiteralNode.parse(ast, new Token<>((long)(int)(Integer) val, 0));
        if (val instanceof Long) return LiteralNode.parse(ast, new Token<>((Long) val, 0));
        if (val instanceof Double) return LiteralNode.parse(ast, new Token<>((Double) val, 0));
        if (val instanceof Float) return LiteralNode.parse(ast, new Token<>((double)(float)(Float) val, 0));
        if (val instanceof Boolean) return LiteralNode.parse(ast, new Token<>((Boolean) val, 0));
        return LiteralNode.parse(ast, new Token<>(val.toString(), 0));
    }
}
