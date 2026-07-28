package org.twelve.meridian.python;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.Assignment;
import org.twelve.gcp.node.expression.identifier.Identifier;
import org.twelve.gcp.node.function.FunctionCallNode;
import org.twelve.gcp.node.function.FunctionNode;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compile-surface tree-shake: drop library functions that are not reachable
 * from the usage entry points (direct calls + intra-library callees).
 *
 * <p>Does not delete from the user's original project — only rewrites the
 * source fed to mypyc. No-usage compiles skip pruning (no evidence).
 */
public final class CompileSourcePruner {

    public record Result(String source, Set<String> kept, Set<String> removed) {}

    private CompileSourcePruner() {}

    /**
     * Remove top-level functions (and {@code name = lambda ...} forms) that are
     * never reached from {@code usageAst} call sites.
     */
    public static Result prune(String librarySource, AST libraryAst, AST usageAst) {
        if (librarySource == null || libraryAst == null || usageAst == null) {
            return new Result(librarySource, Set.of(), Set.of());
        }
        Set<String> defined = collectDefined(libraryAst);
        if (defined.isEmpty()) {
            return new Result(librarySource, Set.of(), Set.of());
        }
        Map<String, Set<String>> callees = collectCalleeGraph(libraryAst, defined);
        Set<String> seeds = collectCallTargets(usageAst.program(), defined);
        Set<String> kept = closure(seeds, callees);
        Set<String> removed = new LinkedHashSet<>(defined);
        removed.removeAll(kept);
        if (removed.isEmpty()) {
            return new Result(librarySource, kept, removed);
        }
        String out = librarySource;
        for (String name : removed) {
            out = removeFunctionOrLambda(out, name);
        }
        // Collapse excessive blank lines left by removals
        out = out.replaceAll("\n{3,}", "\n\n");
        if (out.isBlank()) {
            out = "# Meridian compile surface: no reachable functions from usage\n";
        }
        return new Result(out, kept, removed);
    }

    static Set<String> collectDefined(AST libraryAst) {
        Set<String> names = new LinkedHashSet<>();
        for (var stmt : libraryAst.program().body().statements()) {
            if (!(stmt instanceof VariableDeclarator vd)) continue;
            for (Assignment a : vd.assignments()) {
                if (!(a.rhs() instanceof FunctionNode) || a.lhs() == null) continue;
                String name = a.lhs().lexeme().trim().replaceAll(":.*", "").trim();
                if (!name.isBlank()) names.add(name);
            }
        }
        return names;
    }

    /** Library function → other library functions it calls. */
    static Map<String, Set<String>> collectCalleeGraph(AST libraryAst, Set<String> defined) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (String n : defined) graph.put(n, new LinkedHashSet<>());
        for (var stmt : libraryAst.program().body().statements()) {
            if (!(stmt instanceof VariableDeclarator vd)) continue;
            for (Assignment a : vd.assignments()) {
                if (!(a.rhs() instanceof FunctionNode fn) || a.lhs() == null) continue;
                String name = a.lhs().lexeme().trim().replaceAll(":.*", "").trim();
                if (!defined.contains(name)) continue;
                collectCallTargets(fn, defined, graph.get(name));
            }
        }
        return graph;
    }

    static Set<String> collectCallTargets(Node root, Set<String> defined) {
        Set<String> out = new LinkedHashSet<>();
        collectCallTargets(root, defined, out);
        return out;
    }

    private static void collectCallTargets(Node node, Set<String> defined, Set<String> out) {
        if (node instanceof FunctionCallNode call
                && call.function() instanceof Identifier id
                && defined.contains(id.name())) {
            out.add(id.name());
        }
        for (Node child : node.nodes()) {
            collectCallTargets(child, defined, out);
        }
    }

    static Set<String> closure(Set<String> seeds, Map<String, Set<String>> callees) {
        Set<String> reachable = new LinkedHashSet<>();
        Queue<String> q = new ArrayDeque<>(seeds);
        while (!q.isEmpty()) {
            String n = q.poll();
            if (!reachable.add(n)) continue;
            for (String c : callees.getOrDefault(n, Set.of())) {
                if (!reachable.contains(c)) q.add(c);
            }
        }
        return reachable;
    }

    static String removeFunctionOrLambda(String source, String funcName) {
        String withoutDef = removeFunctionBlock(source, funcName);
        if (!withoutDef.equals(source)) return withoutDef;
        // Module-level: name = lambda ...
        Pattern p = Pattern.compile(
                "^[ \\t]*" + Pattern.quote(funcName)
                        + "[ \\t]*=[ \\t]*lambda[ \\t][^\\n]*\\n?",
                Pattern.MULTILINE);
        Matcher m = p.matcher(source);
        if (m.find()) {
            return source.substring(0, m.start()) + source.substring(m.end());
        }
        return source;
    }

    /** Remove a top-level {@code def name(...):} block including body. */
    static String removeFunctionBlock(String source, String funcName) {
        String[] lines = source.split("\n", -1);
        Pattern defPat = Pattern.compile(
                "^(\\s*)(?:async\\s+)?def\\s+" + Pattern.quote(funcName) + "\\s*\\(");
        int start = -1;
        String baseIndent = null;
        int end = lines.length;

        for (int i = 0; i < lines.length; i++) {
            if (start < 0) {
                Matcher m = defPat.matcher(lines[i]);
                if (m.find()) {
                    start = i;
                    baseIndent = m.group(1);
                }
            } else {
                String ln = lines[i];
                if (!ln.isBlank() && !ln.startsWith(baseIndent + " ")
                        && !ln.startsWith(baseIndent + "\t")
                        && !ln.equals(baseIndent)) {
                    if (ln.matches("\\s*(?:async\\s+)?def\\s+.*")
                            || ln.matches("\\s*class\\s+.*")
                            || (baseIndent.isEmpty() && !ln.startsWith(" ") && !ln.startsWith("\t"))) {
                        end = i;
                        break;
                    }
                }
            }
        }
        if (start < 0) return source;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i >= start && i < end) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        // Preserve trailing newline if original had one after the block
        if (source.endsWith("\n") && !sb.toString().endsWith("\n") && end >= lines.length) {
            sb.append('\n');
        }
        return sb.toString();
    }
}
