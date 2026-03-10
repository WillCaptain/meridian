package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.VariableKind;
import org.twelve.gcp.node.expression.typeable.TypeNode;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.Map;

/**
 * Handles {@code Try}/{@code TryStar}: dispatches all sub-bodies for type inference.
 *
 * <p>Each {@code except} handler is processed by
 * {@link #handleExceptHandler(AST, Map, Node)}, which:
 * <ol>
 *   <li>Declares the handler variable ({@code e} in {@code except ValueError as e:}) with the
 *       exception type so GCP knows {@code e: ValueError} inside the handler body.</li>
 *   <li>Dispatches all statements inside the handler body.</li>
 * </ol>
 */
public class TryConverter extends PyConverter {

    public TryConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        for (Map<String, Object> stmt : listOf(pyNode, "body"))      dispatch(ast, stmt, parent);
        for (Map<String, Object> h    : listOf(pyNode, "handlers"))  handleExceptHandler(ast, h, parent);
        for (Map<String, Object> stmt : listOf(pyNode, "orelse"))    dispatch(ast, stmt, parent);
        for (Map<String, Object> stmt : listOf(pyNode, "finalbody")) dispatch(ast, stmt, parent);
        return null;
    }

    /**
     * Process one {@code ExceptHandler} node.
     *
     * <p>If the handler has both a type ({@code ValueError}) and a name ({@code e}),
     * emits {@code e: ValueError = e} so GCP infers the exception's concrete type.
     */
    private void handleExceptHandler(AST ast, Map<String, Object> handler, Node parent) {
        if (handler == null) return;

        String handlerName = strOf(handler, "name");
        Map<String, Object> handlerType = mapOf(handler, "type");

        if (handlerName != null && handlerType != null) {
            TypeNode typeNode = buildTypeNode(ast, handlerType);
            if (typeNode != null) {
                VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
                decl.declare(identifier(ast, handlerName), typeNode, identifier(ast, handlerName));
                addStatement(ast, parent, decl);
            }
        }

        for (Map<String, Object> stmt : listOf(handler, "body")) dispatch(ast, stmt, parent);
    }
}
