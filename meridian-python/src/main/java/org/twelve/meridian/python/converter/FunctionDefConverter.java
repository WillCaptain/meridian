package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.VariableKind;
import org.twelve.gcp.node.expression.body.FunctionBody;
import org.twelve.gcp.node.expression.typeable.TypeNode;
import org.twelve.gcp.node.function.Argument;
import org.twelve.gcp.node.function.FunctionNode;
import org.twelve.gcp.node.statement.Statement;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles {@code FunctionDef} and {@code AsyncFunctionDef}.
 *
 * <p>Builds arguments from the Python {@code args} node (skipping {@code self}),
 * dispatches the function body with the {@link FunctionBody} as parent so that
 * nested statements are added to the function scope rather than the module scope.
 */
public class FunctionDefConverter extends PyConverter {

    public FunctionDefConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        String name = strOf(pyNode, "name");
        Map<String, Object> argsNode = mapOf(pyNode, "args");

        List<Argument> args = buildArguments(ast, argsNode);
        FunctionBody body = buildFunctionBody(ast, pyNode);
        FunctionNode fn = FunctionNode.from(body, args.toArray(new Argument[0]));

        VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.LET);
        decl.declare(identifier(ast, name), fn);
        addStatement(ast, parent, decl);
        return decl;
    }

    private List<Argument> buildArguments(AST ast, Map<String, Object> argsNode) {
        if (argsNode == null) return new ArrayList<>();
        List<Map<String, Object>> argsList = listOf(argsNode, "args");
        List<Map<String, Object>> defaults = listOf(argsNode, "defaults");
        // Python right-aligns defaults: last defaults.size() positional args have default values.
        int firstDefaultIdx = argsList.size() - defaults.size();

        List<Argument> args = new ArrayList<>();
        for (int i = 0; i < argsList.size(); i++) {
            Map<String, Object> argNode = argsList.get(i);
            String argName = strOf(argNode, "arg");
            if ("self".equals(argName)) continue;

            TypeNode typeNode = buildTypeNode(ast, mapOf(argNode, "annotation"));
            if (typeNode == null && i >= firstDefaultIdx) {
                typeNode = inferDefaultType(ast, defaults.get(i - firstDefaultIdx));
            }
            args.add(new Argument(identifier(ast, argName), typeNode));
        }
        return args;
    }

    /**
     * Infer argument type from its default value literal (e.g. {@code def f(x=0)} → {@code x: int}).
     * Only handles simple constant defaults; complex expressions yield {@code null}.
     */
    static TypeNode inferDefaultType(AST ast, Map<String, Object> defaultVal) {
        if (defaultVal == null) return null;
        if (!"Constant".equals(typeOf(defaultVal))) return null;
        Object val = defaultVal.get("value");
        if (val instanceof Integer || val instanceof Long) return buildTypeNode(ast, nameNode("int"));
        if (val instanceof Double || val instanceof Float) return buildTypeNode(ast, nameNode("float"));
        if (val instanceof Boolean) return buildTypeNode(ast, nameNode("bool"));
        if (val instanceof String) return buildTypeNode(ast, nameNode("str"));
        return null;
    }

    static Map<String, Object> nameNode(String typeName) {
        return Map.of("_type", "Name", "id", typeName);
    }

    private FunctionBody buildFunctionBody(AST ast, Map<String, Object> funcNode) {
        FunctionBody body = new FunctionBody(ast);
        for (Map<String, Object> stmt : listOf(funcNode, "body")) {
            if ("Pass".equals(typeOf(stmt))) continue;
            if (isDocstring(stmt)) continue;
            Node node = dispatch(ast, stmt, body);
            if (node instanceof Statement s && !body.nodes().contains(s)) {
                body.addStatement(s);
            }
        }
        return body;
    }

    private static boolean isDocstring(Map<String, Object> exprStmt) {
        if (!"Expr".equals(typeOf(exprStmt))) return false;
        Map<String, Object> val = mapOf(exprStmt, "value");
        return val != null && "Constant".equals(typeOf(val)) && val.get("value") instanceof String;
    }
}
