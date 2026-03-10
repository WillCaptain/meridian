package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.ast.Token;
import org.twelve.gcp.node.expression.OutlineDefinition;
import org.twelve.gcp.node.expression.identifier.SymbolIdentifier;
import org.twelve.gcp.node.expression.typeable.EntityTypeNode;
import org.twelve.gcp.node.expression.typeable.TypeNode;
import org.twelve.gcp.node.statement.OutlineDeclarator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles {@code ClassDef}: creates a GCP {@link OutlineDeclarator} from an
 * annotated Python class, then dispatches method definitions as top-level functions.
 */
public class ClassDefConverter extends PyConverter {

    public ClassDefConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        String name = strOf(pyNode, "name");
        SymbolIdentifier symId = new SymbolIdentifier(ast, new Token<>(name, 0));

        List<OutlineDefinition> defs = new ArrayList<>();
        defs.add(new OutlineDefinition(symId, new EntityTypeNode(ast)));

        for (Map<String, Object> stmt : listOf(pyNode, "body")) {
            if ("AnnAssign".equals(typeOf(stmt))) {
                Map<String, Object> target = mapOf(stmt, "target");
                String fieldName = strOf(target, "id");
                TypeNode typeNode = buildTypeNode(ast, mapOf(stmt, "annotation"));
                if (fieldName != null && typeNode != null) {
                    defs.add(new OutlineDefinition(
                            new SymbolIdentifier(ast, new Token<>(fieldName, 0)),
                            typeNode));
                }
            }
        }

        OutlineDeclarator decl = new OutlineDeclarator(defs);

        for (Map<String, Object> stmt : listOf(pyNode, "body")) {
            String t = typeOf(stmt);
            if ("FunctionDef".equals(t) || "AsyncFunctionDef".equals(t)) {
                dispatch(ast, stmt, parent);
            }
        }

        ast.addStatement(decl);
        return decl;
    }
}
