package org.twelve.meridian.python.converter;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.common.VariableKind;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.List;
import java.util.Map;

/**
 * Handles Python 3.10+ {@code match/case} structural pattern matching.
 *
 * <p>The GCP strategy mirrors how {@code if/elif/else} is handled: dispatch all
 * case bodies so that assignments and returns inside each branch are visible to
 * the type inference engine. Additionally, capture variables bound by patterns
 * (e.g., {@code case x:}, {@code case [x, y]:}) are declared with the type of
 * the match subject, enabling typed usage inside case blocks.
 *
 * <p>Supported patterns:
 * <ul>
 *   <li>{@code MatchAs(name="x")} — capture variable: {@code x = subject}</li>
 *   <li>{@code MatchStar(name="rest")} — starred capture in sequence patterns</li>
 *   <li>{@code MatchSequence} / {@code MatchMapping} — recurse into sub-patterns</li>
 *   <li>{@code MatchValue} / {@code MatchSingleton} / {@code MatchClass} — no binding</li>
 * </ul>
 *
 * <p>Python AST structure:
 * <pre>{@code
 * Match(
 *   subject = expr,
 *   cases = [
 *     match_case(
 *       pattern = MatchAs(name="x") | MatchValue(...) | ...,
 *       guard   = expr | null,
 *       body    = [stmt, ...]
 *     ), ...
 *   ]
 * )
 * }</pre>
 */
public class MatchConverter extends PyConverter {

    public MatchConverter(Map<String, PyConverter> converters) {
        super(converters);
    }

    @Override
    public Node convert(AST ast, Map<String, Object> pyNode, Node parent) {
        Map<String, Object> subjectNode = mapOf(pyNode, "subject");
        Expression subject = subjectNode != null ? (Expression) dispatch(ast, subjectNode) : null;

        for (Map<String, Object> matchCase : listOf(pyNode, "cases")) {
            Map<String, Object> pattern = mapOf(matchCase, "pattern");
            if (pattern != null && subject != null) {
                declarePatternBindings(ast, parent, pattern, subject);
            }
            for (Map<String, Object> stmt : listOf(matchCase, "body")) {
                dispatch(ast, stmt, parent);
            }
        }
        return null;
    }

    /**
     * Walk a pattern node and emit {@link VariableDeclarator} for each capture variable.
     * The declared initialiser is {@code subject} so GCP can infer the variable's type
     * from the subject expression's inferred outline.
     */
    private void declarePatternBindings(AST ast, Node parent,
                                        Map<String, Object> pattern, Expression subject) {
        String kind = typeOf(pattern);
        if (kind == null) return;

        switch (kind) {
            case "MatchAs" -> {
                // case x: or case expr as x:
                String name = strOf(pattern, "name");
                if (name != null) {
                    VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
                    decl.declare(identifier(ast, name), subject);
                    addStatement(ast, parent, decl);
                }
            }
            case "MatchStar" -> {
                // case [x, *rest, y]: — *rest captures remaining sequence
                String name = strOf(pattern, "name");
                if (name != null) {
                    VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
                    decl.declare(identifier(ast, name), subject);
                    addStatement(ast, parent, decl);
                }
            }
            case "MatchSequence" -> {
                // case [a, b, ...]: — recurse into each sub-pattern
                List<Map<String, Object>> patterns = listOf(pattern, "patterns");
                for (Map<String, Object> sub : patterns) {
                    declarePatternBindings(ast, parent, sub, subject);
                }
            }
            case "MatchMapping" -> {
                // case {"key": val}: — declare rest variable if present
                List<Map<String, Object>> patterns = listOf(pattern, "patterns");
                for (Map<String, Object> sub : patterns) {
                    declarePatternBindings(ast, parent, sub, subject);
                }
                String rest = strOf(pattern, "rest");
                if (rest != null) {
                    VariableDeclarator decl = new VariableDeclarator(ast, VariableKind.VAR);
                    decl.declare(identifier(ast, rest), subject);
                    addStatement(ast, parent, decl);
                }
            }
            case "MatchClass" -> {
                // case Point(x, y): — capture positional args
                List<Map<String, Object>> patterns = listOf(pattern, "patterns");
                for (Map<String, Object> sub : patterns) {
                    declarePatternBindings(ast, parent, sub, subject);
                }
            }
            // MatchValue, MatchSingleton: no capture variables
        }
    }
}
