package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.VariableKind;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.typeable.TypeNode;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.Map;

/** Handles {@code AnnAssign}: {@code x: int = expr} or {@code x: int}. */
public class AnnAssignConverter extends PyConverter {

    public AnnAssignConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Map<String, Object> target = mapOf(pyNode, "target");
        String varName = strOf(target, "id");
        if (varName == null) return null;

        TypeNode typeNode = buildTypeNode(ast, mapOf(pyNode, "annotation"));
        Map<String, Object> valueNode = mapOf(pyNode, "value");
        Expression value = valueNode != null ? (Expression) dispatch(ast, valueNode) : null;

        VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
        decl.declare(identifier(ast, varName), typeNode, value);
        addStatement(ast, parent, decl);
        return decl;
    }
}
