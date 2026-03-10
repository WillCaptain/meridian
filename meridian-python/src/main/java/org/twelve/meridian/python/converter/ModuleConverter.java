package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;

import java.util.Map;

/** Handles {@code Module}: iterates top-level body statements. */
public class ModuleConverter extends PyConverter {

    public ModuleConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Node target = parent != null ? parent : ast.program();
        for (Map<String, Object> stmt : listOf(pyNode, "body")) {
            dispatch(ast, stmt, target);
        }
        return null;
    }
}
