package org.twelve.meridian.python;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.node.expression.Assignment;
import org.twelve.gcp.node.expression.Variable;
import org.twelve.gcp.node.expression.typeable.TypeNode;
import org.twelve.gcp.node.function.Argument;
import org.twelve.gcp.node.function.FunctionNode;
import org.twelve.gcp.node.statement.VariableDeclarator;
import org.twelve.gcp.outline.Outline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a Python source file by inserting inferred type annotations.
 *
 * <p>Strategy:
 * <ol>
 *   <li>Run the full inference pipeline on the source.</li>
 *   <li>Build a map of {@code (line, name) → inferred type} from the GCP AST.</li>
 *   <li>Walk the original Python source line-by-line and splice in annotations:
 *     <ul>
 *       <li>Bare assignments {@code x = expr} that have an inferred type
 *           become {@code x: type = expr}.</li>
 *       <li>Function parameters without annotations get them added.</li>
 *       <li>Function {@code ->} return type is inserted when inferred.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Existing annotations are never overwritten — only unannotated constructs are touched.
 */
public class PythonAnnotationWriter {

    private final TypeAnnotationGenerator typeGen = new TypeAnnotationGenerator();

    // ── public API ─────────────────────────────────────────────────────────────

    /**
     * Write type-annotated Python to {@code outputPath}.
     * If an annotation cannot be inferred for a given construct, the original
     * source line is preserved unchanged.
     *
     * @param originalSource Python source text
     * @param ast            Fully-inferred GCP AST produced from {@code originalSource}
     * @param outputPath     Destination for the annotated source file
     */
    public void write(String originalSource, AST ast, Path outputPath) throws IOException {
        String annotated = annotate(originalSource, ast);
        Files.writeString(outputPath, annotated, StandardCharsets.UTF_8);
    }

    /**
     * Produce an annotated copy of {@code originalSource} using types from {@code ast}.
     */
    public String annotate(String originalSource, AST ast) {
        // Collect inferred types keyed by variable / function name
        Map<String, String> nameToType = collectInferredTypes(ast);
        if (nameToType.isEmpty()) return originalSource;

        String[] lines = originalSource.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(rewriteLine(line, nameToType)).append("\n");
        }
        // Remove the trailing newline we added for the last line if source didn't end with one
        if (!originalSource.endsWith("\n") && out.length() > 0) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    // ── type collection ────────────────────────────────────────────────────────

    private Map<String, String> collectInferredTypes(AST ast) {
        Map<String, String> result = new LinkedHashMap<>();
        for (var stmt : ast.program().body().statements()) {
            if (!(stmt instanceof VariableDeclarator vd)) continue;
            for (Assignment a : vd.assignments()) {
                if (a.lhs() == null) continue;
                // Strip any existing type annotation from the lexeme (e.g. "x: Int" → "x")
                String rawName = a.lhs().lexeme().trim().replaceAll(":.*", "").trim();
                if (rawName.isBlank()) continue;

                // Skip if there is already a declared (user-written) annotation
                TypeNode declared = (a.lhs() instanceof Variable v) ? v.declared() : null;
                if (declared != null && typeGen.typeNodeToStr(declared) != null) continue;

                // Use inferred outline type
                if (a.rhs() instanceof FunctionNode fn) {
                    collectFunctionTypes(rawName, fn, result);
                } else {
                    Outline inferred = a.rhs() != null ? a.rhs().outline() : null;
                    String typeStr = typeGen.outlineToTypeStr(inferred);
                    if (typeStr != null) result.put(rawName, typeStr);
                }
            }
        }
        return result;
    }

    private void collectFunctionTypes(String funcName, FunctionNode fn,
                                      Map<String, String> result) {
        // Collect argument types
        Argument arg = fn.argument();
        if (arg != null) {
            String argName = arg.lexeme().trim().replaceAll(":.*", "").trim();
            TypeNode declared = arg.declared();
            if (declared == null) {
                String typeStr = typeGen.outlineToTypeStr(arg.outline());
                if (typeStr != null) result.put(funcName + "#" + argName, typeStr);
            }
        }
        // Inferred return type
        Outline ret = fn.outline();
        String retStr = typeGen.outlineToTypeStr(ret);
        if (retStr != null) result.put(funcName + "#return", retStr);
    }

    // ── source rewriting ───────────────────────────────────────────────────────

    // Patterns for bare assignment:  x = expr   (no existing annotation)
    private static final Pattern BARE_ASSIGN = Pattern.compile(
            "^(\\s*)([A-Za-z_][A-Za-z0-9_]*)\\s*=(?!=)\\s*(.+)$");

    // Pattern for function def:  def func(args):
    private static final Pattern FUNC_DEF = Pattern.compile(
            "^(\\s*(?:async\\s+)?def\\s+)([A-Za-z_][A-Za-z0-9_]*)\\s*\\((.*)\\)\\s*(?:->\\s*[^:]+)?:\\s*$");

    private String rewriteLine(String line, Map<String, String> nameToType) {
        // Only annotate module-level bare assignments (no leading whitespace).
        // Inside functions, local-variable types are inferred by mypy/mypyc automatically,
        // and injecting annotations for iteration variables causes "name already defined" errors.
        Matcher m = BARE_ASSIGN.matcher(line);
        if (m.matches()) {
            String indent = m.group(1);
            String name   = m.group(2);
            String rest   = m.group(3);
            // Skip indented (function/class body) assignments to avoid loop-variable conflicts
            if (indent.isEmpty()) {
                String typeStr = nameToType.get(name);
                if (typeStr != null) {
                    return name + ": " + typeStr + " = " + rest;
                }
            }
        }

        // Try function def: inject return type if missing
        Matcher fm = FUNC_DEF.matcher(line);
        if (fm.matches()) {
            String prefix  = fm.group(1);
            String name    = fm.group(2);
            String args    = fm.group(3);
            // Only add -> return if there's no existing one
            if (!line.contains("->")) {
                String retType = nameToType.get(name + "#return");
                if (retType != null) {
                    // Reconstruct: def name(args) -> retType:
                    return prefix + name + "(" + rewriteArgs(name, args, nameToType) + ") -> " + retType + ":";
                }
            }
            return prefix + name + "(" + rewriteArgs(name, args, nameToType) + "):";
        }

        return line;
    }

    /**
     * Inject parameter type annotations that are missing from the original arg list.
     * E.g. "x, y: int" → "x: int, y: int" if x's type was inferred.
     */
    private String rewriteArgs(String funcName, String args, Map<String, String> nameToType) {
        if (args.isBlank()) return args;
        String[] params = args.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            String param = params[i].trim();
            if (param.isEmpty()) { sb.append(param); continue; }
            // Skip self / cls
            if (param.equals("self") || param.equals("cls")) { sb.append(param); continue; }
            // Already annotated?
            if (param.contains(":") || param.contains("=")) { sb.append(param); continue; }
            String typeStr = nameToType.get(funcName + "#" + param);
            if (typeStr != null) {
                sb.append(param).append(": ").append(typeStr);
            } else {
                sb.append(param);
            }
        }
        return sb.toString();
    }
}
