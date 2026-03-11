package org.twelve.meridian.python;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.Assignment;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.Variable;
import org.twelve.gcp.node.expression.identifier.Identifier;
import org.twelve.gcp.node.expression.typeable.TypeNode;
import org.twelve.gcp.node.function.Argument;
import org.twelve.gcp.node.function.FunctionCallNode;
import org.twelve.gcp.node.function.FunctionNode;
import org.twelve.gcp.node.statement.VariableDeclarator;
import org.twelve.gcp.outline.Outline;
import org.twelve.gcp.outline.builtin.UNKNOWN;
import org.twelve.gcp.outline.projectable.Genericable;

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
        return annotate(originalSource, ast, Collections.emptyMap());
    }

    /**
     * Demand-driven annotation: augment type inference with call-site information
     * collected from a usage AST.
     *
     * <p>The {@code usageAst} is scanned for {@link FunctionCallNode} instances.
     * For each call whose name matches a function in {@code libraryAst}, the concrete
     * argument types (which GCP infers from call-site literals) are mapped back to
     * the function's parameter names and injected as annotations.
     *
     * @param originalSource the library Python source to annotate
     * @param libraryAst     the inferred GCP AST for the library
     * @param usageAst       a second AST containing call sites with concrete argument types
     */
    public String annotate(String originalSource, AST libraryAst, AST usageAst) {
        Map<String, String> nameToType = collectInferredTypes(libraryAst);
        augmentFromCallSites(nameToType, libraryAst, usageAst);
        return rewrite(originalSource, nameToType);
    }

    /**
     * Core annotation: apply a pre-built type map (possibly augmented by call-site analysis).
     */
    public String annotate(String originalSource, AST ast, Map<String, String> extraHints) {
        Map<String, String> nameToType = collectInferredTypes(ast);
        nameToType.putAll(extraHints);
        return rewrite(originalSource, nameToType);
    }

    private String rewrite(String originalSource, Map<String, String> nameToType) {
        if (nameToType.isEmpty()) return originalSource;
        String[] lines = originalSource.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            out.append(rewriteLine(line, nameToType)).append("\n");
        }
        if (!originalSource.endsWith("\n") && out.length() > 0) {
            out.setLength(out.length() - 1);
        }
        String result = out.toString();
        // Inject Iterator import when needed and not already present as an import statement.
        // Check for "import Iterator" specifically (not just "Iterator") to avoid a false positive
        // when the annotation "-> Iterator[int]" was just injected into a function signature.
        boolean needsIterator = nameToType.values().stream().anyMatch(v -> v.contains("Iterator["));
        if (needsIterator && !result.contains("import Iterator")) {
            result = "from typing import Iterator\n" + result;
        }
        return result;
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
        for (Argument arg : typeGen.flattenFunctionArgs(fn)) {
            TypeNode declared = arg.declared();
            if (declared != null) {
                // Use declared type (from user annotation OR inferred from default value)
                String s = typeGen.typeNodeToStr(declared);
                if (s != null) result.put(funcName + "#" + arg.name(), s);
            } else {
                // No declared type: use GCP inferred outline type
                String typeStr = typeGen.outlineToTypeStr(arg.outline());
                if (typeStr != null) result.put(funcName + "#" + arg.name(), typeStr);
            }
        }
        // Return type
        String retStr = typeGen.functionReturnType(fn);
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

    // ── demand-driven call-site analysis ─────────────────────────────────────

    /**
     * Scan {@code usageAst} for function calls and, for each call that matches a
     * function defined in {@code libraryAst}, record the concrete argument types
     * under the key {@code "funcName#paramName"} in {@code out}.
     *
     * <p>This is a name-based (not symbol-based) analysis: it matches calls by the
     * callee identifier name rather than by resolving cross-module imports.
     * This keeps the implementation simple while correctly handling the common case
     * where the usage context calls library functions with typed literals.
     */
    /**
     * Propagate call-site argument types from {@code usageAst} into the library's function
     * parameter constraints (Genericable hasToBe), so that {@link TypeAnnotationGenerator}
     * can see the concrete types when generating stubs.
     *
     * <p>This is a lightweight version of {@link #augmentFromCallSites} that only does
     * constraint propagation without building a type map — call it from the inference pipeline
     * so stubs are correct even when the full annotate pipeline is not run.
     */
    public void propagateCallSiteTypes(AST libraryAst, AST usageAst) {
        augmentFromCallSites(new LinkedHashMap<>(), libraryAst, usageAst);
    }

    private void augmentFromCallSites(Map<String, String> out, AST libraryAst, AST usageAst) {
        // Build func → [Argument, ...] from the library AST (we need the Argument objects to
        // propagate call-site types back into the library's Genericable parameter constraints).
        Map<String, List<Argument>> funcArgs = new LinkedHashMap<>();
        Map<String, List<String>> funcParams = new LinkedHashMap<>();
        for (var stmt : libraryAst.program().body().statements()) {
            if (stmt instanceof VariableDeclarator vd) {
                for (Assignment a : vd.assignments()) {
                    if (a.rhs() instanceof FunctionNode fn) {
                        String fname = a.lhs().lexeme().trim().replaceAll(":.*", "").trim();
                        List<Argument> args = typeGen.flattenFunctionArgs(fn);
                        funcArgs.put(fname, args);
                        funcParams.put(fname, args.stream().map(Argument::name).toList());
                    }
                }
            }
        }
        if (funcParams.isEmpty()) return;

        // Traverse usage AST and collect call-site argument types
        scanCallSites(usageAst.program(), funcParams, funcArgs, out);
    }

    private void scanCallSites(Node node, Map<String, List<String>> funcParams,
                                Map<String, List<Argument>> funcArgs,
                                Map<String, String> out) {
        if (node instanceof FunctionCallNode call) {
            if (call.function() instanceof Identifier id) {
                String fname = id.name();
                List<String> params = funcParams.get(fname);
                List<Argument> libArgs = funcArgs.get(fname);
                if (params != null) {
                    List<Expression> args = call.arguments();
                    for (int i = 0; i < Math.min(params.size(), args.size()); i++) {
                        Expression argNode = args.get(i);
                        // Force-infer if the argument was not resolved by the joint inference pass.
                        // This happens when the callee (e.g. a library function) is not in scope of
                        // the usage AST (no import), so FunctionCallInference returned Pending and
                        // the argument nodes never had their own inference triggered.
                        // Dict/Array/Literal arguments are self-contained and safe to infer here.
                        if (argNode.outline() instanceof UNKNOWN) {
                            argNode.infer(argNode.ast().inferences());
                        }
                        Outline argOutline = argNode.outline();
                        String typeStr = typeGen.outlineToTypeStr(argOutline);
                        if (typeStr != null) {
                            // putIfAbsent: first call site wins; never overwrite declared types
                            out.putIfAbsent(fname + "#" + params.get(i), typeStr);
                        }
                        // Propagate the call-site type into the library parameter's Genericable
                        // constraint so that stub generation (TypeAnnotationGenerator) can see it.
                        // Only skip when the parameter already has a user-written type annotation.
                        // Structural constraints set by GCP from body usage (e.g. definedToBe from
                        // subscript access) are NOT a reason to skip — they should be compatible with
                        // the call-site type and addHasToBe will validate that internally.
                        if (libArgs != null && i < libArgs.size() && argOutline != null
                                && !(argOutline instanceof UNKNOWN)) {
                            Argument libArg = libArgs.get(i);
                            if (libArg.declared() == null) {
                                Outline paramOutline = libArg.outline();
                                if (paramOutline instanceof Genericable<?, ?> g) {
                                    g.addHasToBe(argOutline);
                                }
                            }
                        }
                    }
                }
            }
        }
        for (Node child : node.nodes()) {
            scanCallSites(child, funcParams, funcArgs, out);
        }
    }

    /**
     * Inject parameter type annotations that are missing from the original arg list.
     *
     * <p>Handles three cases:
     * <ul>
     *   <li>{@code n}       → {@code n: int}  (bare param with inferred type)</li>
     *   <li>{@code n=1000}  → {@code n: int = 1000}  (default value, add type before {@code =})</li>
     *   <li>{@code n: int}  → unchanged  (already annotated)</li>
     * </ul>
     */
    private String rewriteArgs(String funcName, String args, Map<String, String> nameToType) {
        if (args.isBlank()) return args;
        String[] params = args.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) sb.append(", ");
            String param = params[i].trim();
            if (param.isEmpty()) { sb.append(param); continue; }
            if (param.equals("self") || param.equals("cls")) { sb.append(param); continue; }

            // Already has a type annotation (colon appears before any equals sign)
            if (hasTypeAnnotation(param)) { sb.append(param); continue; }

            // Split off default value if present: "n=1000" → name="n", default="= 1000"
            int eqIdx = param.indexOf('=');
            String paramName   = eqIdx >= 0 ? param.substring(0, eqIdx).trim() : param;
            String defaultPart = eqIdx >= 0 ? " " + param.substring(eqIdx).trim() : null;

            // Strip */** prefix for lookup (e.g. "*args" → "args"), restore in output
            String argPrefix   = "";
            String lookupName  = paramName;
            if (paramName.startsWith("**")) {
                argPrefix  = "**";
                lookupName = paramName.substring(2);
            } else if (paramName.startsWith("*")) {
                argPrefix  = "*";
                lookupName = paramName.substring(1);
            }

            String typeStr = nameToType.get(funcName + "#" + lookupName);
            if (typeStr != null) {
                sb.append(argPrefix).append(lookupName).append(": ").append(typeStr);
                if (defaultPart != null) sb.append(defaultPart);
            } else {
                sb.append(param);
            }
        }
        return sb.toString();
    }

    /** Returns {@code true} when a parameter string already carries a type annotation. */
    private static boolean hasTypeAnnotation(String param) {
        int colonIdx  = param.indexOf(':');
        int equalsIdx = param.indexOf('=');
        // Annotation present if ':' exists and comes before '=' (or '=' is absent)
        return colonIdx >= 0 && (equalsIdx < 0 || colonIdx < equalsIdx);
    }
}
