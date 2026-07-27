package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.VariableKind;
import org.twelve.gcp.node.expression.Assignable;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.identifier.Identifier;
import org.twelve.gcp.node.statement.VariableDeclarator;
import org.twelve.gcp.node.unpack.TupleUnpackNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles {@code Assign}: {@code x = expr} and tuple unpack {@code a, b = expr}
 * / {@code a, *b, c = expr} / nested {@code c, (d, e) = expr}.
 */
public class AssignConverter extends PyConverter {

    public AssignConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Map<String, Object> valueNode = mapOf(pyNode, "value");
        Expression value = valueNode != null ? (Expression) dispatch(ast, valueNode) : null;

        VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
        boolean declared = false;

        for (Map<String, Object> target : listOf(pyNode, "targets")) {
            Assignable assignable = buildAssignable(ast, target);
            if (assignable != null) {
                decl.declare(assignable, value);
                declared = true;
            }
        }

        if (!declared) return null;
        addStatement(ast, parent, decl);
        return decl;
    }

    private Assignable buildAssignable(AST ast, Map<String, Object> target) {
        String t = typeOf(target);
        if ("Name".equals(t)) {
            return identifier(ast, strOf(target, "id"), target);
        }
        if ("Tuple".equals(t) || "List".equals(t)) {
            return buildTupleUnpack(ast, target);
        }
        return null;
    }

    /**
     * Build a {@link TupleUnpackNode} for {@code a, b = …}, {@code a, *rest, c = …},
     * and nested patterns {@code c, (d, e) = …}.
     */
    private TupleUnpackNode buildTupleUnpack(AST ast, Map<String, Object> target) {
        List<Node> begins = new ArrayList<>();
        List<Node> ends = new ArrayList<>();
        Identifier rest = null;
        boolean starSeen = false;

        for (Map<String, Object> elt : listOf(target, "elts")) {
            if ("Starred".equals(typeOf(elt))) {
                starSeen = true;
                Map<String, Object> starValue = mapOf(elt, "value");
                if (starValue != null && "Name".equals(typeOf(starValue))) {
                    rest = identifier(ast, strOf(starValue, "id"), starValue);
                }
                continue;
            }
            Node piece = unpackPiece(ast, elt);
            if (piece == null) continue;
            if (starSeen) {
                ends.add(piece);
            } else {
                begins.add(piece);
            }
        }
        return new TupleUnpackNode(ast, begins, rest, ends);
    }

    private Node unpackPiece(AST ast, Map<String, Object> node) {
        String t = typeOf(node);
        if ("Name".equals(t)) {
            return identifier(ast, strOf(node, "id"), node);
        }
        if ("Tuple".equals(t) || "List".equals(t)) {
            return buildTupleUnpack(ast, node);
        }
        return null;
    }
}
