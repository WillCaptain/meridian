package org.twelve.meridian.python;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.node.expression.Assignment;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.identifier.Identifier;
import org.twelve.gcp.node.function.Argument;
import org.twelve.gcp.node.function.FunctionCallNode;
import org.twelve.gcp.node.function.FunctionNode;
import org.twelve.gcp.node.statement.VariableDeclarator;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Monomorphization for Python functions via call-site analysis.
 *
 * <h2>Motivation</h2>
 * GCP's type system is parametrically polymorphic: {@code let f = x -> x} leaves
 * {@code x} as a generic type variable.  At a call site {@code f(10)}, GCP can
 * determine that {@code x: Integer} for that particular invocation.
 *
 * <p>This class exploits that knowledge to generate <em>type-specialized</em>
 * copies of Python functions — one per unique argument-type tuple observed at
 * call sites.  Each copy carries concrete type annotations, enabling mypyc to
 * generate fully-typed C extensions.
 *
 * <h2>Strategy</h2>
 * <pre>
 *   def add(x, y): return x + y
 *
 *   Call sites (usage context):
 *     add(1, 2)       → (int, int)
 *     add(1.0, 2.0)   → (float, float)
 *
 *   Generated specializations:
 *     def add(x: int,   y: int)   -> int:   return x + y   # original rewritten
 *     def _add_float(x: float, y: float) -> float: return x + y
 *     # dispatcher: if all sites use same type, only one version is emitted.
 * </pre>
 *
 * <h2>Naming convention</h2>
 * <ul>
 *   <li>If every call site uses the same type, the <em>original</em> function is
 *       annotated in place — no new name.</li>
 *   <li>If multiple type combinations exist, the dominant (most frequent) type
 *       annotates the original; additional specializations get a suffix:
 *       {@code _add_float}, {@code _add_int_str}, …</li>
 * </ul>
 *
 * <h2>Constraints</h2>
 * No GCP code is modified.  Specialization is a pure post-processing step in
 * meridian that rewrites Python source text using the type information that GCP
 * already exposes through call-site argument outlines.
 */
public class FunctionSpecializer {

    private final TypeAnnotationGenerator typeGen = new TypeAnnotationGenerator();

    // ── public data model ─────────────────────────────────────────────────────

    /** One concrete type-binding for a single call site. */
    public record TypeBinding(
            String funcName,          // "add"
            List<String> paramNames,  // ["x", "y"]
            List<String> argTypes,    // ["int", "float"]
            String returnType         // "int" (inferred from library body)
    ) {
        /** E.g. "int_float" — used for generating a unique suffix. */
        public String typeSig() {
            return argTypes.stream()
                    .map(t -> t == null ? "any" : t.toLowerCase()
                            .replaceAll("[^a-z0-9]", ""))
                    .collect(Collectors.joining("_"));
        }

        /** The specialized function name, or the original if this is the primary binding. */
        public String specName(boolean isPrimary) {
            return isPrimary ? funcName : "_" + funcName + "_" + typeSig();
        }
    }

    /** All specializations discovered for a single function. */
    public record FuncSpecializations(
            String funcName,
            List<TypeBinding> bindings   // distinct type tuples, sorted by frequency
    ) {
        public TypeBinding primary()    { return bindings.getFirst(); }
        public boolean isMonomorphic()  { return bindings.size() == 1; }
    }

    // ── main API ──────────────────────────────────────────────────────────────

    /**
     * Analyse call sites in {@code usageAst} against function definitions in
     * {@code libraryAst} and produce per-function specialization plans.
     *
     * @param libraryAst  inferred AST of the library being optimised
     * @param usageAst    inferred AST of the usage/benchmark context
     * @return map from function name to its {@link FuncSpecializations}
     */
    public Map<String, FuncSpecializations> analyse(AST libraryAst, AST usageAst) {
        // ── step 1: extract parameter names + return types from library ────────
        Map<String, List<String>> funcParams  = new LinkedHashMap<>();
        Map<String, String>       funcReturns = new LinkedHashMap<>();
        for (var stmt : libraryAst.program().body().statements()) {
            if (!(stmt instanceof VariableDeclarator vd)) continue;
            for (Assignment a : vd.assignments()) {
                if (!(a.rhs() instanceof FunctionNode fn)) continue;
                String name = a.lhs().lexeme().trim().replaceAll(":.*", "").trim();
                funcParams.put(name, typeGen.flattenFunctionArgs(fn).stream()
                        .map(Argument::name).toList());
                funcReturns.put(name, typeGen.functionReturnType(fn));
            }
        }

        // ── step 2: collect call-site type tuples ─────────────────────────────
        // freq map: funcName → (typeTuple → count)
        // retMap:  funcName → (typeTuple → specializedReturnType)
        Map<String, Map<List<String>, Integer>> freq    = new LinkedHashMap<>();
        Map<String, Map<List<String>, String>>  retMap  = new LinkedHashMap<>();
        scanCallSites(usageAst.program(), funcParams, freq, retMap);

        // ── step 3: build FuncSpecializations ─────────────────────────────────
        Map<String, FuncSpecializations> result = new LinkedHashMap<>();
        for (var entry : freq.entrySet()) {
            String fname = entry.getKey();
            List<String> params = funcParams.getOrDefault(fname, List.of());
            // Fallback to library-body-inferred return type if call site has no info
            String bodyRet = funcReturns.get(fname);

            // Sort by frequency descending so the most-used type is the "primary"
            List<TypeBinding> bindings = entry.getValue().entrySet().stream()
                    .sorted(Map.Entry.<List<String>, Integer>comparingByValue().reversed())
                    .map(e -> {
                        // Prefer the specialized return type from the call expression outline;
                        // fall back to body-inferred return type, then to projecting from arg types
                        String specRet = retMap.getOrDefault(fname, Map.of()).get(e.getKey());
                        String retType = (specRet != null) ? specRet : bodyRet;
                        // If still null, project: e.g. int×int→int, float×float→float
                        if (retType == null) retType = projectNumericReturn(e.getKey());
                        return new TypeBinding(fname, params, e.getKey(), retType);
                    })
                    .toList();

            result.put(fname, new FuncSpecializations(fname, bindings));
        }
        return result;
    }

    /**
     * Rewrite {@code originalSource} according to the specialization plan.
     *
     * <ul>
     *   <li>The <em>primary</em> (most-frequent) type binding is applied to the
     *       original function definition in place.</li>
     *   <li>Additional (secondary) type bindings are appended as new function
     *       definitions with suffixed names; their recursive calls are rewritten
     *       to use the same suffixed name.</li>
     * </ul>
     */
    public String specialize(String originalSource, Map<String, FuncSpecializations> plan) {
        String source = originalSource;

        // ── pass 1: annotate original functions in place ───────────────────────
        for (FuncSpecializations fs : plan.values()) {
            TypeBinding primary = fs.primary();
            source = annotateInPlace(source, primary.funcName(),
                    primary.paramNames(), primary.argTypes(), primary.returnType());
        }

        // ── pass 2: append extra specializations for secondary type tuples ─────
        StringBuilder extra = new StringBuilder();
        for (FuncSpecializations fs : plan.values()) {
            if (fs.isMonomorphic()) continue;   // nothing extra to emit
            for (int i = 1; i < fs.bindings().size(); i++) {
                TypeBinding sec = fs.bindings().get(i);
                String specFunc = buildSpecializedFunction(
                        originalSource, sec, false);
                if (specFunc != null) extra.append(specFunc);
            }
        }
        if (!extra.isEmpty()) {
            source = source + "\n# ── GCP demand-driven specializations ─────────────────\n"
                    + extra;
        }
        return source;
    }

    // ── in-place annotation ───────────────────────────────────────────────────

    private static String annotateInPlace(String source,
                                          String funcName,
                                          List<String> paramNames,
                                          List<String> argTypes,
                                          String returnType) {
        // Match a single-line def header only (no DOTALL — keeps body intact)
        Pattern p = Pattern.compile(
                "^([ \t]*(?:async[ \t]+)?def[ \t]+" + Pattern.quote(funcName)
                + "[ \t]*\\()(.*?)(\\)[ \t]*(?:->[ \t]*[^:]+)?:)[ \t]*$",
                Pattern.MULTILINE);
        Matcher m = p.matcher(source);
        if (!m.find()) return source;

        String prefix       = m.group(1);
        String existingArgs = m.group(2);
        String existingSuffix = m.group(3); // e.g. "):" or ") -> int:"

        // Rewrite argument list — inject missing type annotations
        String newArgs = rewriteArgs(existingArgs, paramNames, argTypes);

        // Inject return type when absent and we have one to offer
        String newSuffix;
        if (!existingSuffix.contains("->") && returnType != null) {
            newSuffix = ") -> " + returnType + ":";
        } else {
            newSuffix = existingSuffix;
        }

        return source.substring(0, m.start())
                + prefix + newArgs + newSuffix
                + source.substring(m.end());
    }

    private static String rewriteArgs(String existing,
                                      List<String> names,
                                      List<String> types) {
        if (existing.isBlank()) return existing;
        String[] parts = existing.split(",");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(", ");
            String p = parts[i].trim();
            if (p.equals("self") || p.equals("cls") || p.contains(":") || p.contains("=")) {
                sb.append(p);
                continue;
            }
            // Find this param's index by name
            int idx = names.indexOf(p);
            String t = (idx >= 0 && idx < types.size()) ? types.get(idx) : null;
            if (t != null) sb.append(p).append(": ").append(t);
            else sb.append(p);
        }
        return sb.toString();
    }

    // ── specialization function builder ──────────────────────────────────────

    /**
     * Extract the original function's source block from the library source,
     * rename it to {@code specName}, inject type annotations, and rewrite any
     * self-recursive calls.
     */
    private static String buildSpecializedFunction(String source,
                                                    TypeBinding binding,
                                                    boolean isPrimary) {
        String origName = binding.funcName();
        String specName = binding.specName(isPrimary);
        String block    = extractFunctionBlock(source, origName);
        if (block == null) return null;

        // Rename function header
        block = block.replaceFirst(
                "(def\\s+)" + Pattern.quote(origName) + "(\\s*\\()",
                "$1" + specName + "$2");

        // Annotate parameters
        block = annotateInPlace(block, specName,
                binding.paramNames(), binding.argTypes(), binding.returnType());

        // Rewrite recursive calls: origName(...) → specName(...)
        // Only replace calls, not the def line (already renamed above)
        block = rewriteRecursiveCalls(block, origName, specName);

        return block + "\n";
    }

    /**
     * Extract the source of a top-level function definition (including its body).
     * Stops when a line with the same or lesser indentation level is encountered
     * (i.e., the next top-level definition).
     */
    private static String extractFunctionBlock(String source, String funcName) {
        String[] lines = source.split("\n", -1);
        Pattern defPat = Pattern.compile(
                "^(\\s*)(?:async\\s+)?def\\s+" + Pattern.quote(funcName) + "\\s*\\(");
        int start = -1;
        String baseIndent = null;

        for (int i = 0; i < lines.length; i++) {
            if (start < 0) {
                Matcher m = defPat.matcher(lines[i]);
                if (m.find()) {
                    start = i;
                    baseIndent = m.group(1);
                }
            } else {
                // End at a non-empty line at the same or lesser indent that is NOT inside the func
                String ln = lines[i];
                if (!ln.isBlank() && !ln.startsWith(baseIndent + " ")
                        && !ln.startsWith(baseIndent + "\t")
                        && !ln.equals(baseIndent)) {
                    // Could be the start of the next function
                    if (ln.matches("\\s*(?:async\\s+)?def\\s+.*") || ln.matches("\\s*class\\s+.*")) {
                        return String.join("\n", Arrays.copyOfRange(lines, start, i)) + "\n";
                    }
                }
            }
        }
        if (start < 0) return null;
        return String.join("\n", Arrays.copyOfRange(lines, start, lines.length)) + "\n";
    }

    /** Replace recursive calls {@code origName(...)} with {@code specName(...)} in the body. */
    private static String rewriteRecursiveCalls(String block, String origName, String specName) {
        // Replace call-site occurrences (not the def line itself, already renamed)
        // Pattern: origName followed by '(' but not in a def line
        String[] lines = block.split("\n", -1);
        Pattern callPat = Pattern.compile("\\b" + Pattern.quote(origName) + "\\s*\\(");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            // Skip the def line
            if (line.matches("\\s*(?:async\\s+)?def\\s+" + Pattern.quote(specName) + "\\s*\\(.*")) {
                sb.append(line).append("\n");
                continue;
            }
            sb.append(callPat.matcher(line).replaceAll(specName + "(")).append("\n");
        }
        return sb.toString();
    }

    // ── type projection helpers ───────────────────────────────────────────────

    /**
     * When GCP cannot determine a concrete return type (null from body inference),
     * project a best-effort return type from the observed argument types.
     *
     * <p>For pure numeric arithmetic functions ({@code int}/{@code float} args only):
     * <ul>
     *   <li>all-int  → {@code int}</li>
     *   <li>any-float → {@code float}</li>
     * </ul>
     * Returns {@code null} for anything else (mypyc will still infer from body).
     */
    private static String projectNumericReturn(List<String> argTypes) {
        if (argTypes == null || argTypes.isEmpty()) return null;
        boolean hasFloat = argTypes.stream().anyMatch("float"::equals);
        boolean allNumeric = argTypes.stream()
                .allMatch(t -> t != null && (t.equals("int") || t.equals("float")));
        if (!allNumeric) return null;
        return hasFloat ? "float" : "int";
    }

    // ── call-site scanner ─────────────────────────────────────────────────────

    private void scanCallSites(Node node,
                                Map<String, List<String>> funcParams,
                                Map<String, Map<List<String>, Integer>> freq,
                                Map<String, Map<List<String>, String>> callReturnTypes) {
        if (node instanceof FunctionCallNode call
                && call.function() instanceof Identifier id) {
            String fname = id.name();
            if (funcParams.containsKey(fname)) {
                List<String> argTypes = call.arguments().stream()
                        .map(arg -> typeGen.outlineToTypeStr(arg.outline()))
                        .toList();
                freq.computeIfAbsent(fname, k -> new LinkedHashMap<>())
                    .merge(argTypes, 1, Integer::sum);

                // The call expression's outline is the SPECIALIZED return type
                // (e.g. add(1,2).outline() = INTEGER, not the generic Addable)
                String specRetType = typeGen.outlineToTypeStr(call.outline());
                callReturnTypes.computeIfAbsent(fname, k -> new LinkedHashMap<>())
                               .putIfAbsent(argTypes, specRetType);
            }
        }
        for (Node child : node.nodes()) {
            scanCallSites(child, funcParams, freq, callReturnTypes);
        }
    }
}
