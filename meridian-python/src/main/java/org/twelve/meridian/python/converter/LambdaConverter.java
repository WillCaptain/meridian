package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.body.FunctionBody;
import org.twelve.gcp.node.expression.typeable.TypeNode;
import org.twelve.gcp.node.function.Argument;
import org.twelve.gcp.node.function.FunctionNode;
import org.twelve.gcp.node.statement.ReturnStatement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles Python {@code Lambda}: {@code lambda x, y: expr}.
 *
 * <p>A lambda is converted to an anonymous {@link FunctionNode} with:
 * <ul>
 *   <li>Each parameter as an {@link Argument} (type inferred from default value if present).</li>
 *   <li>A single {@link ReturnStatement} wrapping the body expression.</li>
 * </ul>
 *
 * <p>This allows GCP to infer the lambda's input/output types and for mypyc to
 * compile calls through it with full type information.
 */
public class LambdaConverter extends PyConverter {

    public LambdaConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Map<String, Object> argsNode = mapOf(pyNode, "args");
        Map<String, Object> bodyNode = mapOf(pyNode, "body");
        if (bodyNode == null) return null;

        List<Argument> args = buildLambdaArgs(ast, argsNode);

        FunctionBody funcBody = new FunctionBody(ast);
        Expression bodyExpr = (Expression) dispatch(ast, bodyNode);
        if (bodyExpr != null) {
            funcBody.addStatement(new ReturnStatement(bodyExpr));
        }

        return FunctionNode.from(funcBody, args.toArray(new Argument[0]));
    }

    private List<Argument> buildLambdaArgs(AST ast, Map<String, Object> argsNode) {
        List<Argument> args = new ArrayList<>();
        if (argsNode == null) return args;

        List<Map<String, Object>> argsList = listOf(argsNode, "args");
        List<Map<String, Object>> defaults = listOf(argsNode, "defaults");
        int firstDefaultIdx = argsList.size() - defaults.size();

        for (int i = 0; i < argsList.size(); i++) {
            Map<String, Object> argNode = argsList.get(i);
            String argName = strOf(argNode, "arg");
            if (argName == null) continue;

            TypeNode typeNode = buildTypeNode(ast, mapOf(argNode, "annotation"));
            if (typeNode == null && i >= firstDefaultIdx) {
                typeNode = FunctionDefConverter.inferDefaultType(ast, defaults.get(i - firstDefaultIdx));
            }
            args.add(new Argument(identifier(ast, argName), typeNode));
        }
        return args;
    }
}
