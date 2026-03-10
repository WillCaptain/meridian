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
 * Handles {@code Assign}: {@code x = expr} and tuple unpack {@code a, b = expr}.
 *
 * <p>Simple name targets create a {@link VariableDeclarator}.
 * Tuple targets (P0) create a {@link TupleUnpackNode} as the assignable, enabling
 * GCP to infer individual element types from multi-valued returns.
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
            return identifier(ast, strOf(target, "id"));
        }
        if ("Tuple".equals(t) || "List".equals(t)) {
            return buildTupleUnpack(ast, target);
        }
        return null;
    }

    /** Build a {@link TupleUnpackNode} for patterns like {@code a, b = ...} or {@code a, *b, c = ...}. */
    private TupleUnpackNode buildTupleUnpack(AST ast, Map<String, Object> target) {
        List<Node> begins = new ArrayList<>();
        List<Node> ends = new ArrayList<>();
        boolean starSeen = false;

        for (Map<String, Object> elt : listOf(target, "elts")) {
            if ("Starred".equals(typeOf(elt))) {
                starSeen = true;
                continue;
            }
            Identifier id = resolveIdentifier(ast, elt);
            if (id == null) continue;
            if (starSeen) {
                ends.add(id);
            } else {
                begins.add(id);
            }
        }
        return new TupleUnpackNode(ast, begins, ends);
    }

    private Identifier resolveIdentifier(AST ast, Map<String, Object> node) {
        if ("Name".equals(typeOf(node))) return identifier(ast, strOf(node, "id"));
        return null;
    }
}
