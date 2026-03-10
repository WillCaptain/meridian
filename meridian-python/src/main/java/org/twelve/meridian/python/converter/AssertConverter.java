package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.VariableKind;
import org.twelve.gcp.node.expression.identifier.Identifier;
import org.twelve.gcp.node.expression.typeable.TypeNode;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.List;
import java.util.Map;

/**
 * Handles {@code Assert} statements, with special support for {@code isinstance} type narrowing.
 *
 * <p>{@code assert isinstance(x, int)} is a very common Python guard pattern. After such an
 * assert the variable {@code x} is guaranteed to be of type {@code int}. This converter emits
 * a {@link VariableDeclarator} with an explicit {@link TypeNode} declaration so GCP (and mypyc)
 * treats {@code x} as the narrowed type for the rest of the function.
 *
 * <p>Supports both single-type and tuple-of-types forms:
 * <ul>
 *   <li>{@code assert isinstance(x, int)} → {@code x: int}</li>
 *   <li>{@code assert isinstance(x, (int, float))} → uses the first listed type</li>
 * </ul>
 *
 * <p>Non-isinstance asserts are silently ignored (no GCP node emitted).
 */
public class AssertConverter extends PyConverter {

    public AssertConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Map<String, Object> test = mapOf(pyNode, "test");
        if (test == null) return null;

        // Also dispatch to allow NamedExpr inside assert conditions
        dispatch(ast, test, parent);

        if (!"Call".equals(typeOf(test))) return null;
        Map<String, Object> func = mapOf(test, "func");
        if (!"isinstance".equals(strOf(func, "id"))) return null;

        List<Map<String, Object>> args = listOf(test, "args");
        if (args.size() < 2) return null;

        Map<String, Object> varNode  = args.get(0);
        Map<String, Object> typeArg  = args.get(1);
        if (!"Name".equals(typeOf(varNode))) return null;

        String varName = strOf(varNode, "id");
        if (varName == null) return null;

        // Resolve the type: either a Name or a Tuple of names (use first element)
        TypeNode narrowed = resolveIsinstanceType(ast, typeArg);
        if (narrowed == null) return null;

        // Declare x: T = x  — same value, but with an explicit declared type annotation
        Identifier id = identifier(ast, varName);
        VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
        decl.declare(id, narrowed, id);
        addStatement(ast, parent, decl);
        return decl;
    }

    private TypeNode resolveIsinstanceType(AST ast, Map<String, Object> typeArg) {
        String t = typeOf(typeArg);
        if ("Name".equals(t) || "Attribute".equals(t)) {
            return buildTypeNode(ast, typeArg);
        }
        // isinstance(x, (int, float)) — tuple of types: use the first
        if ("Tuple".equals(t)) {
            List<Map<String, Object>> elts = listOf(typeArg, "elts");
            if (!elts.isEmpty()) return buildTypeNode(ast, elts.get(0));
        }
        return null;
    }
}
