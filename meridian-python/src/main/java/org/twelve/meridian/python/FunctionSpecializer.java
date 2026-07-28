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
 * GCP/Outline treat parametric functions as optional at definition time
 * ({@code let f = x -> x} leaves {@code x} open). Each call site may bind a
 * different concrete type — {@code f("s")} vs {@code f(1)}, {@code add(1,2)} vs
 * {@code add(1.0,2.0)}, container variants, etc. {@code str}/{@code int} is only
 * one instance of that pattern; <strong>every</strong> distinct concrete
 * call-site type tuple is handled the same way.
 *
 * <p>This class generates <em>type-specialized</em> copies — one per unique
 * argument-type tuple — so mypyc receives fully concrete annotations.
 *
 * <h2>Strategy</h2>
 * <pre>
 *   def f(x): return x + x
 *
 *   Call sites (any concrete Outline/GCP bindings):
 *     f(1) / f(2)           → (int,)
 *     f("a") / f("bb")      → (str,)
 *     f(1.0)                → (float,)   # same rule, other types
 *
 *   Generated:
 *     def f(x):                      # isinstance dispatcher (fallback / external)
 *         if isinstance(x, int): return _f_int(x)
 *         if isinstance(x, str): return _f_str(x)
 *         ...
 *     def _f_int(x: int) -> int: ...
 *     def _f_str(x: str) -> str: ...
 *
 *   Library-internal calls with a concrete Outline binding are rewritten to the
 *   clone ({@code helper(i)} → {@code _helper_int(i)}) so hot paths skip the
 *   dispatcher.
 * </pre>
 *
 * <h2>Naming convention</h2>
 * <ul>
 *   <li>One concrete type tuple → annotate the original in place.</li>
 *   <li>Multiple concrete tuples → dispatcher at the original name +
 *       {@code _name_<typesig>} clones for every tuple.</li>
 *   <li>Call sites are collected from <em>usage and library</em> ASTs so
 *       callees of hot functions enter the plan even when usage only names
 *       the entry point.</li>
 * </ul>
 *
 * <h2>Constraints</h2>
 * No GCP code is modified.  Specialization is a pure post-processing step in
 * meridian that rewrites Python source text using call-site argument outlines.
 * Call sites whose argument outlines are not concrete PEP-484 types are ignored.
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
        // Usage + library: callees inside hot loops must enter the plan even when
        // usage only invokes the entry function.
        Map<String, Map<List<String>, Integer>> freq    = new LinkedHashMap<>();
        Map<String, Map<List<String>, String>>  retMap  = new LinkedHashMap<>();
        if (usageAst != null) {
            scanCallSites(usageAst.program(), funcParams, freq, retMap);
        }
        if (libraryAst != null) {
            scanCallSites(libraryAst.program(), funcParams, freq, retMap);
        }

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
                        // Body inference often stays on the numeric tower (Addable →
                        // Union[int,float]) even for str/str call sites. Prefer a
                        // homogeneous projection from the concrete arg tuple.
                        String projected = projectReturnFromArgTypes(e.getKey());
                        if (projected != null
                                && (retType == null
                                || retType.equals("Union[int, float]")
                                || retType.equals("float")
                                || !AnnotationPolicy.isConcrete(retType))) {
                            retType = projected;
                        }
                        if (retType == null) retType = projected;
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
     *   <li>Module-level {@code name = lambda ...} forms for planned functions
     *       are lifted to {@code def} so mypyc can annotate them.</li>
     *   <li>Monomorphic functions: annotate the original definition in place.</li>
     *   <li>Polymorphic functions (any multi-concrete Outline/GCP call-site
     *       bindings — not limited to {@code str}/{@code int}): emit one
     *       {@code _name_typesig} clone per tuple and replace the original name
     *       with an {@code isinstance} dispatcher.</li>
     *   <li>When {@code libraryAst} is provided, library-internal calls with a
     *       matching concrete binding are rewritten to the clone name.</li>
     * </ul>
     */
    public String specialize(String originalSource, Map<String, FuncSpecializations> plan) {
        return specialize(originalSource, plan, null);
    }

    public String specialize(String originalSource, Map<String, FuncSpecializations> plan,
                             AST libraryAst) {
        if (plan == null || plan.isEmpty()) return originalSource;

        String source = convertPlannedLambdasToDefs(originalSource, plan.keySet());
        // Snapshot for extracting bodies before headers are rewritten.
        String defSource = source;

        StringBuilder extra = new StringBuilder();
        for (FuncSpecializations fs : plan.values()) {
            if (fs.isMonomorphic()) {
                TypeBinding primary = fs.primary();
                source = annotateInPlace(source, primary.funcName(),
                        primary.paramNames(), primary.argTypes(), primary.returnType());
                continue;
            }

            // Polymorphic: every binding becomes a concrete clone; original → dispatcher.
            for (TypeBinding binding : fs.bindings()) {
                String specFunc = buildSpecializedFunction(defSource, binding, false);
                if (specFunc != null) extra.append(specFunc);
            }
            String dispatcher = buildDispatcher(fs);
            String replaced = replaceFunctionBlock(source, fs.funcName(), dispatcher);
            if (replaced != null) {
                source = replaced;
            } else {
                // No def block found — append dispatcher after extras.
                extra.append(dispatcher);
            }
        }
        if (!extra.isEmpty()) {
            source = source + "\n# ── GCP demand-driven specializations ─────────────────\n"
                    + extra;
        }
        if (libraryAst != null) {
            source = rewriteLibraryCallsToClones(source, libraryAst, plan);
        }
        return ensureTypingImports(source);
    }

    /**
     * Rewrite library-internal {@code f(...)} calls to {@code _f_<sig>(...)} when
     * the call-site Outline binding matches a polymorphic specialization.
     */
    String rewriteLibraryCallsToClones(
            String source, AST libraryAst, Map<String, FuncSpecializations> plan) {
        if (source == null || libraryAst == null || plan == null || plan.isEmpty()) {
            return source;
        }
        Map<String, String> rewrites = new LinkedHashMap<>();
        collectCloneRewrites(libraryAst.program(), plan, rewrites);
        if (rewrites.isEmpty()) return source;
        String out = source;
        List<Map.Entry<String, String>> ordered = new ArrayList<>(rewrites.entrySet());
        ordered.sort((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()));
        for (Map.Entry<String, String> e : ordered) {
            if (e.getKey().equals(e.getValue())) continue;
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    private void collectCloneRewrites(
            Node node, Map<String, FuncSpecializations> plan, Map<String, String> out) {
        if (node instanceof FunctionCallNode call
                && call.function() instanceof Identifier id) {
            FuncSpecializations fs = plan.get(id.name());
            if (fs != null && !fs.isMonomorphic()) {
                List<String> argTypes = call.arguments().stream()
                        .map(arg -> typeGen.outlineToTypeStr(arg.outline()))
                        .toList();
                if (argTypes.stream().allMatch(AnnotationPolicy::isConcrete)
                        && argTypes.stream().allMatch(t -> runtimeTypeName(t) != null)) {
                    TypeBinding match = null;
                    for (TypeBinding b : fs.bindings()) {
                        if (b.argTypes().equals(argTypes)) {
                            match = b;
                            break;
                        }
                    }
                    if (match != null) {
                        String oldCall = call.lexeme();
                        String args = call.arguments().stream()
                                .map(Expression::lexeme)
                                .collect(Collectors.joining(","));
                        String newCall = match.specName(false) + "(" + args + ")";
                        out.putIfAbsent(oldCall, newCall);
                    }
                }
            }
        }
        for (Node child : node.nodes()) {
            collectCloneRewrites(child, plan, out);
        }
    }

    /**
     * Whether the plan contains any function with multiple concrete type tuples.
     */
    public static boolean needsPolymorphicDispatch(Map<String, FuncSpecializations> plan) {
        if (plan == null) return false;
        return plan.values().stream().anyMatch(fs -> !fs.isMonomorphic());
    }

    // ── lambda lift / dispatcher / block replace ──────────────────────────────

    /** Lift {@code name = lambda args: body} to a def for each planned name. */
    static String convertPlannedLambdasToDefs(String source, Set<String> names) {
        String out = source;
        for (String name : names) {
            Pattern p = Pattern.compile(
                    "^([ \\t]*)" + Pattern.quote(name)
                            + "[ \\t]*=[ \\t]*lambda[ \\t]*([^:]*):(.*)$",
                    Pattern.MULTILINE);
            Matcher m = p.matcher(out);
            if (!m.find()) continue;
            String indent = m.group(1);
            String args = m.group(2).trim();
            String body = m.group(3).trim();
            String def = indent + "def " + name + "(" + args + "):\n"
                    + indent + "    return " + body;
            out = out.substring(0, m.start()) + def + out.substring(m.end());
        }
        return out;
    }

    static String buildDispatcher(FuncSpecializations fs) {
        List<String> params = fs.primary().paramNames();
        String args = String.join(", ", params);
        StringBuilder sb = new StringBuilder();
        sb.append("def ").append(fs.funcName()).append("(").append(args).append("):\n");
        for (TypeBinding b : fs.bindings()) {
            sb.append("    if ").append(isinstanceGuard(params, b.argTypes())).append(":\n");
            sb.append("        return ").append(b.specName(false))
                    .append("(").append(args).append(")\n");
        }
        sb.append("    raise TypeError(")
                .append("\"no Meridian specialization for ").append(fs.funcName()).append("\"")
                .append(")\n");
        return sb.toString();
    }

    private static String isinstanceGuard(List<String> params, List<String> types) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            String t = (i < types.size()) ? types.get(i) : null;
            String pyType = runtimeTypeName(t);
            if (pyType == null) {
                throw new IllegalStateException(
                        "Cannot dispatch on non-runtime type: " + t);
            }
            parts.add("isinstance(" + params.get(i) + ", " + pyType + ")");
        }
        return parts.isEmpty() ? "True" : String.join(" and ", parts);
    }

    /**
     * Map a concrete annotation string to a runtime type for {@code isinstance}.
     * Generic over all concrete Outline/GCP call-site types we can erase to a
     * Python builtin/constructor — not a {@code str}/{@code int} special case.
     */
    static String runtimeTypeName(String typeStr) {
        if (typeStr == null) return null;
        String t = typeStr.trim();
        return switch (t) {
            case "int" -> "int";
            case "float" -> "float";
            case "str" -> "str";
            case "bool" -> "bool";
            case "bytes" -> "bytes";
            case "complex" -> "complex";
            default -> {
                if (t.startsWith("list[") || t.equals("list")) yield "list";
                if (t.startsWith("dict[") || t.equals("dict")) yield "dict";
                if (t.startsWith("tuple[") || t.equals("tuple")) yield "tuple";
                if (t.startsWith("set[") || t.equals("set")) yield "set";
                // Optional[T] / Union[...] are not single runtime tags — skip.
                yield null;
            }
        };
    }

    /** Replace a top-level function block with {@code replacement} (must end with newline). */
    static String replaceFunctionBlock(String source, String funcName, String replacement) {
        String block = extractFunctionBlock(source, funcName);
        if (block == null) return null;
        int idx = source.indexOf(block);
        if (idx < 0) {
            // extractFunctionBlock may normalize trailing newline — try trim match
            String trimmed = block.endsWith("\n") ? block.substring(0, block.length() - 1) : block;
            idx = source.indexOf(trimmed);
            if (idx < 0) return null;
            String repl = replacement.endsWith("\n") ? replacement : replacement + "\n";
            return source.substring(0, idx) + repl + source.substring(idx + trimmed.length());
        }
        String repl = replacement.endsWith("\n") ? replacement : replacement + "\n";
        return source.substring(0, idx) + repl + source.substring(idx + block.length());
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
     * Project a return type from a concrete argument-type tuple.
     * Generic over Outline call-site bindings (not numeric-only): homogeneous
     * tuples collapse to that type; numeric towers prefer float when mixed.
     */
    static String projectReturnFromArgTypes(List<String> argTypes) {
        if (argTypes == null || argTypes.isEmpty()) return null;
        if (argTypes.stream().anyMatch(t -> t == null || !AnnotationPolicy.isConcrete(t))) {
            return null;
        }
        boolean allSame = argTypes.stream().distinct().count() == 1;
        if (allSame) return argTypes.getFirst();
        boolean allNumeric = argTypes.stream()
                .allMatch(t -> t.equals("int") || t.equals("float"));
        if (allNumeric) {
            return argTypes.stream().anyMatch("float"::equals) ? "float" : "int";
        }
        return null;
    }

    /** Inject {@code typing} imports required by emitted annotations. */
    static String ensureTypingImports(String source) {
        String out = source;
        if (out.contains("Union[") && !out.contains("import Union")) {
            out = "from typing import Union\n" + out;
        }
        if (out.contains("Optional[") && !out.contains("import Optional")) {
            out = "from typing import Optional\n" + out;
        }
        if (out.contains("Callable[") && !out.contains("import Callable")) {
            out = "from typing import Callable\n" + out;
        }
        return out;
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
                // Only fully concrete tuples participate — same rule for every
                // Outline optional/parametric binding (str/int/float/list/…).
                if (argTypes.stream().allMatch(AnnotationPolicy::isConcrete)
                        && argTypes.stream().allMatch(t -> runtimeTypeName(t) != null)) {
                    freq.computeIfAbsent(fname, k -> new LinkedHashMap<>())
                        .merge(argTypes, 1, Integer::sum);

                    // Call expression outline = specialized return for this tuple.
                    String specRetType = typeGen.outlineToTypeStr(call.outline());
                    if (specRetType != null && !AnnotationPolicy.isConcrete(specRetType)) {
                        specRetType = null;
                    }
                    callReturnTypes.computeIfAbsent(fname, k -> new LinkedHashMap<>())
                                   .putIfAbsent(argTypes, specRetType);
                }
            }
        }
        for (Node child : node.nodes()) {
            scanCallSites(child, funcParams, freq, callReturnTypes);
        }
    }
}
