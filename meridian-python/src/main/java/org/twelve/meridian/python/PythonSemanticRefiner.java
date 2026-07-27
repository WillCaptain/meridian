package org.twelve.meridian.python;

import org.twelve.gcp.ast.AST;
import org.twelve.meridian.python.converter.PyConverter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Language-specific refinement on top of GCP: Python semantics that do not map
 * 1:1 into GCP AST. Knows nothing about TypeEvalPy sites / FR / FP / LV.
 *
 * <p>Active rules:
 * <ol>
 *   <li>Call-site argument → parameter type constraints</li>
 *   <li>Container literal element projection ({@code d['foo']}, {@code xs[0]})</li>
 *   <li>Returned-callable / binder data-flow ({@code x = f}; {@code return other})</li>
 *   <li>Receiver-sensitive method returns ({@code obj = C(); obj.m()})</li>
 *   <li>{@code self.attr = self.method} delegation / {@code return self.attr()}</li>
 *   <li>Import-alias resolution against registered / sibling module sources</li>
 * </ol>
 */
public final class PythonSemanticRefiner {

    public PythonInferenceResult refine(AST gcpAst, Map<String, Object> pyAst,
                                        String fileName, Path sourcePath) {
        return refine(gcpAst, pyAst, fileName, sourcePath, Map.of());
    }

    /**
     * @param moduleSources optional {@code moduleName → source} (registry / siblings) for
     *                      precise import-alias resolution; never scans unrelated files.
     */
    public PythonInferenceResult refine(AST gcpAst, Map<String, Object> pyAst,
                                        String fileName, Path sourcePath,
                                        Map<String, String> moduleSources) {
        Map<String, List<List<String>>> callSiteArgTypes = collectCallArgTypes(pyAst);
        Map<String, List<String>> containerElements = new LinkedHashMap<>();
        Map<String, List<String>> callReturns = new LinkedHashMap<>();
        Map<String, List<String>> methodReturns = new LinkedHashMap<>();
        Map<String, List<String>> functionReturns = new LinkedHashMap<>();
        Map<String, String> receiverTypes = new LinkedHashMap<>();
        Map<String, List<String>> callResults = new LinkedHashMap<>();
        Map<String, String> importAliases = collectImportAliases(pyAst);
        Map<String, Map<String, String>> attrBindings = collectAttrMethodBindings(pyAst);
        Map<String, List<String>> classAttrs = collectClassAttrLiterals(pyAst);

        indexImportedFunctionReturns(moduleSources, sourcePath, importAliases,
                functionReturns, methodReturns, classAttrs);
        indexFunctionReturns(pyAst, functionReturns, methodReturns);
        applyImportAliasesToLocalBinders(importAliases, functionReturns, callReturns);
        resolveSelfAttrDelegation(pyAst, attrBindings, methodReturns, functionReturns, classAttrs);
        projectContainerLiterals(pyAst, containerElements, callReturns, functionReturns);
        bindReturnedCallablesAndReceivers(
                pyAst, functionReturns, methodReturns, callReturns, receiverTypes, callResults);

        Map<String, Map<String, List<String>>> refinedParams =
                refineParamsFromCallSites(pyAst, callSiteArgTypes);

        return new PythonInferenceResult(
                gcpAst,
                pyAst,
                fileName,
                sourcePath,
                callSiteArgTypes,
                containerElements,
                callReturns,
                refinedParams,
                methodReturns,
                callResults,
                receiverTypes,
                importAliases,
                attrBindings,
                classAttrs);
    }

    public PythonInferenceResult refine(AST gcpAst, Map<String, Object> pyAst, String fileName) {
        return refine(gcpAst, pyAst, fileName, null);
    }

    // ── Rule 1: call-site arg → param ─────────────────────────────────────────

    static Map<String, List<List<String>>> collectCallArgTypes(Map<String, Object> pyModule) {
        Map<String, List<List<String>>> out = new HashMap<>();
        walkCallsForArgTypes(pyModule, out);
        return out;
    }

    private static void walkCallsForArgTypes(Map<String, Object> node,
                                             Map<String, List<List<String>>> out) {
        if (node == null || node.isEmpty()) return;
        if ("Call".equals(PyConverter.typeOf(node))) {
            Map<String, Object> func = PyConverter.mapOf(node, "func");
            String callee = null;
            if ("Name".equals(PyConverter.typeOf(func))) {
                callee = PyConverter.strOf(func, "id");
            } else if ("Attribute".equals(PyConverter.typeOf(func))) {
                callee = PyConverter.strOf(func, "attr");
            }
            if (callee != null) {
                List<List<String>> args = new ArrayList<>();
                for (Map<String, Object> arg : PyConverter.listOf(node, "args")) {
                    args.add(argTypeEvidence(arg));
                }
                List<List<String>> prev = out.get(callee);
                if (prev == null) {
                    out.put(callee, args);
                } else {
                    int n = Math.max(prev.size(), args.size());
                    List<List<String>> merged = new ArrayList<>();
                    for (int i = 0; i < n; i++) {
                        List<String> a = i < prev.size() ? prev.get(i) : List.of();
                        List<String> b = i < args.size() ? args.get(i) : List.of();
                        merged.add(unionTypes(a, b));
                    }
                    out.put(callee, merged);
                }
            }
        }
        for (Object v : node.values()) {
            walkChild(v, out);
        }
    }

    private static void walkChild(Object v, Map<String, List<List<String>>> out) {
        if (v instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> child = (Map<String, Object>) m;
            walkCallsForArgTypes(child, out);
        } else if (v instanceof List<?> list) {
            for (Object o : list) {
                walkChild(o, out);
            }
        }
    }

    private static List<String> argTypeEvidence(Map<String, Object> arg) {
        List<String> t = literalType(arg);
        if (!t.isEmpty() && !t.equals(List.of("callable"))) {
            return t;
        }
        if ("Name".equals(PyConverter.typeOf(arg))) {
            String id = PyConverter.strOf(arg, "id");
            if (id != null && !id.isEmpty() && Character.isUpperCase(id.charAt(0))) {
                return List.of(id);
            }
            return List.of();
        }
        if ("Attribute".equals(PyConverter.typeOf(arg))) {
            return List.of();
        }
        if ("Call".equals(PyConverter.typeOf(arg))) {
            // Nested call args: square(add(2, 3)) / square(add(2.1, 3.2)) — propagate
            // numeric literal evidence so FP/FR keep int|float across call sites.
            if (callArgsIntroduceFloat(arg)) return List.of("float");
            if (callArgsOnlyInts(arg)) return List.of("int");
            return List.of();
        }
        return List.of();
    }

    /** True when any nested arg is a float literal (or a call/binop that introduces float). */
    private static boolean callArgsIntroduceFloat(Map<String, Object> call) {
        if (call == null || !"Call".equals(PyConverter.typeOf(call))) return false;
        for (Map<String, Object> a : PyConverter.listOf(call, "args")) {
            if (exprIntroducesFloatLit(a)) return true;
        }
        return false;
    }

    /** True when the call has ≥1 arg and every arg is proven int (literals / nested int calls). */
    private static boolean callArgsOnlyInts(Map<String, Object> call) {
        if (call == null || !"Call".equals(PyConverter.typeOf(call))) return false;
        List<Map<String, Object>> args = PyConverter.listOf(call, "args");
        if (args.isEmpty()) return false;
        for (Map<String, Object> a : args) {
            if (!argIsIntEvidence(a)) return false;
        }
        return true;
    }

    private static boolean exprIntroducesFloatLit(Map<String, Object> expr) {
        if (expr == null) return false;
        if (literalType(expr).equals(List.of("float"))) return true;
        if ("Call".equals(PyConverter.typeOf(expr))) return callArgsIntroduceFloat(expr);
        if ("BinOp".equals(PyConverter.typeOf(expr))) {
            return exprIntroducesFloatLit(PyConverter.mapOf(expr, "left"))
                    || exprIntroducesFloatLit(PyConverter.mapOf(expr, "right"));
        }
        if ("UnaryOp".equals(PyConverter.typeOf(expr))) {
            return exprIntroducesFloatLit(PyConverter.mapOf(expr, "operand"));
        }
        return false;
    }

    private static boolean argIsIntEvidence(Map<String, Object> arg) {
        if (arg == null) return false;
        if (literalType(arg).equals(List.of("int"))) return true;
        if ("Call".equals(PyConverter.typeOf(arg))) {
            return callArgsOnlyInts(arg);
        }
        if ("UnaryOp".equals(PyConverter.typeOf(arg))) {
            return argIsIntEvidence(PyConverter.mapOf(arg, "operand"));
        }
        if ("BinOp".equals(PyConverter.typeOf(arg))) {
            return argIsIntEvidence(PyConverter.mapOf(arg, "left"))
                    && argIsIntEvidence(PyConverter.mapOf(arg, "right"));
        }
        return false;
    }

    private static Map<String, Map<String, List<String>>> refineParamsFromCallSites(
            Map<String, Object> pyModule,
            Map<String, List<List<String>>> callSiteArgTypes) {
        Map<String, Map<String, List<String>>> out = new LinkedHashMap<>();
        if (pyModule == null) return out;
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            refineParamsInStmt(stmt, null, callSiteArgTypes, out);
        }
        return out;
    }

    private static void refineParamsInStmt(
            Map<String, Object> stmt,
            String className,
            Map<String, List<List<String>>> callSiteArgTypes,
            Map<String, Map<String, List<String>>> out) {
        String t = PyConverter.typeOf(stmt);
        if ("ClassDef".equals(t)) {
            String name = PyConverter.strOf(stmt, "name");
            for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
                refineParamsInStmt(bodyStmt, name, callSiteArgTypes, out);
            }
            return;
        }
        if (!"FunctionDef".equals(t) && !"AsyncFunctionDef".equals(t)) return;

        String bare = PyConverter.strOf(stmt, "name");
        if (bare == null) return;
        String qname = className != null ? className + "." + bare : bare;
        List<List<String>> observed = bestCallArgTypes(callSiteArgTypes, qname, bare, className);
        if (observed.isEmpty()) return;

        Map<String, List<String>> params = new LinkedHashMap<>();
        int argIndex = 0;
        for (Map<String, Object> arg : functionPositionalArgs(stmt)) {
            String param = PyConverter.strOf(arg, "arg");
            if (param == null || param.isBlank() || "self".equals(param) || "cls".equals(param)) {
                continue;
            }
            if (argIndex < observed.size() && !observed.get(argIndex).isEmpty()) {
                params.put(param, observed.get(argIndex));
            }
            argIndex++;
        }
        if (!params.isEmpty()) {
            out.put(qname, params);
            if (!qname.equals(bare)) {
                out.putIfAbsent(bare, params);
            }
        }
    }

    static List<List<String>> bestCallArgTypes(Map<String, List<List<String>>> callArgTypes,
                                               String qname, String bare, String className) {
        List<List<String>> hits = callArgTypes.get(bare);
        if ((hits == null || hits.isEmpty()) && className != null) {
            hits = callArgTypes.get(className);
        }
        if ((hits == null || hits.isEmpty()) && qname != null) {
            hits = callArgTypes.get(qname);
        }
        return hits != null ? hits : List.of();
    }

    private static List<Map<String, Object>> functionPositionalArgs(Map<String, Object> func) {
        Map<String, Object> argsNode = PyConverter.mapOf(func, "args");
        if (argsNode == null) return List.of();
        return PyConverter.listOf(argsNode, "args");
    }

    // ── Rule 2: container element projection ──────────────────────────────────

    private static void projectContainerLiterals(Map<String, Object> pyModule,
                                                 Map<String, List<String>> elements,
                                                 Map<String, List<String>> callReturns,
                                                 Map<String, List<String>> functionReturns) {
        if (pyModule == null) return;
        walkAssignsForContainers(pyModule, elements, callReturns, functionReturns);
    }

    private static void walkAssignsForContainers(Map<String, Object> node,
                                                 Map<String, List<String>> elements,
                                                 Map<String, List<String>> callReturns,
                                                 Map<String, List<String>> functionReturns) {
        if (node == null || node.isEmpty()) return;
        String t = PyConverter.typeOf(node);
        // Preserve program order so {@code merged = d1 | d2} sees d1/d2 keys.
        if ("Module".equals(t) || "FunctionDef".equals(t) || "AsyncFunctionDef".equals(t)
                || "ClassDef".equals(t)) {
            for (Map<String, Object> stmt : PyConverter.listOf(node, "body")) {
                walkAssignsForContainers(stmt, elements, callReturns, functionReturns);
            }
            return;
        }
        if ("Assign".equals(t)) {
            Map<String, Object> value = PyConverter.mapOf(node, "value");
            for (Map<String, Object> target : PyConverter.listOf(node, "targets")) {
                projectNameBinding(target, value, elements, callReturns, functionReturns);
            }
            return;
        }
        if ("If".equals(t) || "For".equals(t) || "While".equals(t) || "With".equals(t)) {
            for (Map<String, Object> stmt : PyConverter.listOf(node, "body")) {
                walkAssignsForContainers(stmt, elements, callReturns, functionReturns);
            }
            for (Map<String, Object> stmt : PyConverter.listOf(node, "orelse")) {
                walkAssignsForContainers(stmt, elements, callReturns, functionReturns);
            }
            return;
        }
        for (Object v : node.values()) {
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) m;
                walkAssignsForContainers(child, elements, callReturns, functionReturns);
            } else if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> child = (Map<String, Object>) m;
                        walkAssignsForContainers(child, elements, callReturns, functionReturns);
                    }
                }
            }
        }
    }

    private static void projectNameBinding(Map<String, Object> target,
                                           Map<String, Object> value,
                                           Map<String, List<String>> elements,
                                           Map<String, List<String>> callReturns,
                                           Map<String, List<String>> functionReturns) {
        if (!"Name".equals(PyConverter.typeOf(target))) return;
        String name = PyConverter.strOf(target, "id");
        if (name == null || value == null) return;

        if ("List".equals(PyConverter.typeOf(value))) {
            List<Map<String, Object>> elts = PyConverter.listOf(value, "elts");
            for (int i = 0; i < elts.size(); i++) {
                Map<String, Object> elt = elts.get(i);
                List<String> et = literalType(elt);
                if ("Name".equals(PyConverter.typeOf(elt))) {
                    String id = PyConverter.strOf(elt, "id");
                    List<String> fr = id == null ? null : functionReturns.get(id);
                    if (fr != null) callReturns.put(name + "[" + i + "]", fr);
                    if (et.isEmpty()) et = List.of("callable");
                }
                if (et.isEmpty() || et.equals(List.of("Any"))) continue;
                elements.put(name + "[" + i + "]", et);
            }
        } else if ("Dict".equals(PyConverter.typeOf(value))) {
            projectDictElements(name, value, elements, callReturns, functionReturns);
        } else if ("BinOp".equals(PyConverter.typeOf(value))
                && "BitOr".equals(PyConverter.typeOf(PyConverter.mapOf(value, "op")))) {
            // merged = d1 | d2 — only when both sides are Names (dict merge, not int|).
            boolean any = false;
            for (String side : List.of("left", "right")) {
                Map<String, Object> src = PyConverter.mapOf(value, side);
                if (!"Name".equals(PyConverter.typeOf(src))) continue;
                String srcName = PyConverter.strOf(src, "id");
                if (srcName == null) continue;
                String prefix = srcName + "[";
                for (Map.Entry<String, List<String>> e : List.copyOf(elements.entrySet())) {
                    if (!e.getKey().startsWith(prefix)) continue;
                    String suffix = e.getKey().substring(srcName.length());
                    elements.putIfAbsent(name + suffix, e.getValue());
                    any = true;
                }
            }
            if (!any) {
                // Literal dict | literal dict
                for (String side : List.of("left", "right")) {
                    Map<String, Object> src = PyConverter.mapOf(value, side);
                    if ("Dict".equals(PyConverter.typeOf(src))) {
                        projectDictElements(name, src, elements, callReturns, functionReturns);
                    }
                }
            }
        }
    }

    private static void projectDictElements(String dictName,
                                            Map<String, Object> dictLit,
                                            Map<String, List<String>> elements,
                                            Map<String, List<String>> callReturns,
                                            Map<String, List<String>> functionReturns) {
        List<Map<String, Object>> keys = PyConverter.listOf(dictLit, "keys");
        List<Map<String, Object>> values = PyConverter.listOf(dictLit, "values");
        int n = Math.min(keys.size(), values.size());
        for (int i = 0; i < n; i++) {
            Map<String, Object> key = keys.get(i);
            Map<String, Object> val = values.get(i);
            if (key == null) continue;
            String path = constantIndexSite(dictName, key);
            if (path == null) continue;
            List<String> et = literalType(val);
            if ("Name".equals(PyConverter.typeOf(val))) {
                String id = PyConverter.strOf(val, "id");
                List<String> fr = id == null ? null : functionReturns.get(id);
                if (fr != null) callReturns.put(path, fr);
                if (et.isEmpty()) et = List.of("callable");
            }
            if (et.isEmpty()) continue;
            elements.put(path, et);
            if ("Dict".equals(PyConverter.typeOf(val))) {
                projectDictElements(path, val, elements, callReturns, functionReturns);
            }
        }
    }

    // ── Rule 3+4: returned callable + receiver-sensitive methods ──────────────

    private static void indexFunctionReturns(Map<String, Object> pyModule,
                                             Map<String, List<String>> functionReturns,
                                             Map<String, List<String>> methodReturns) {
        if (pyModule == null) return;
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            indexFunctionReturnsInStmt(stmt, null, functionReturns, methodReturns);
        }
        // Second pass: resolve {@code return other_func} once other_func is indexed.
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            resolveReturnedNameReturns(stmt, null, functionReturns, methodReturns);
        }
    }

    private static void indexFunctionReturnsInStmt(Map<String, Object> stmt,
                                                   String className,
                                                   Map<String, List<String>> functionReturns,
                                                   Map<String, List<String>> methodReturns) {
        String t = PyConverter.typeOf(stmt);
        if ("ClassDef".equals(t)) {
            String name = PyConverter.strOf(stmt, "name");
            for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
                indexFunctionReturnsInStmt(bodyStmt, name, functionReturns, methodReturns);
            }
            return;
        }
        if (!"FunctionDef".equals(t) && !"AsyncFunctionDef".equals(t)) return;
        String bare = PyConverter.strOf(stmt, "name");
        if (bare == null) return;
        String qname = className != null ? className + "." + bare : bare;
        List<String> ret = returnTypesFromBody(stmt);
        if (!ret.isEmpty()) {
            functionReturns.put(bare, ret);
            functionReturns.put(qname, ret);
            if (className != null) {
                methodReturns.put(qname, ret);
            }
        }
    }

    private static void resolveReturnedNameReturns(Map<String, Object> stmt,
                                                   String className,
                                                   Map<String, List<String>> functionReturns,
                                                   Map<String, List<String>> methodReturns) {
        String t = PyConverter.typeOf(stmt);
        if ("ClassDef".equals(t)) {
            String name = PyConverter.strOf(stmt, "name");
            for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
                resolveReturnedNameReturns(bodyStmt, name, functionReturns, methodReturns);
            }
            return;
        }
        if (!"FunctionDef".equals(t) && !"AsyncFunctionDef".equals(t)) return;
        String bare = PyConverter.strOf(stmt, "name");
        if (bare == null) return;
        String qname = className != null ? className + "." + bare : bare;
        for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
            if (!"Return".equals(PyConverter.typeOf(bodyStmt))) continue;
            Map<String, Object> value = PyConverter.mapOf(bodyStmt, "value");
            if (!"Name".equals(PyConverter.typeOf(value))) continue;
            String other = PyConverter.strOf(value, "id");
            List<String> otherRet = functionReturns.get(other);
            if (otherRet == null || otherRet.isEmpty()) continue;
            // Returning a function object: call of this function yields a callable whose
            // subsequent call uses otherRet. Represented on callReturns of binders.
            functionReturns.putIfAbsent(bare, List.of("callable"));
            functionReturns.putIfAbsent(qname, List.of("callable"));
            // Alias: calling the returned callable uses other's return.
            functionReturns.put(bare + "()", otherRet);
            functionReturns.put(qname + "()", otherRet);
            if (className != null) {
                methodReturns.putIfAbsent(qname, List.of("callable"));
                methodReturns.put(qname + "()", otherRet);
            }
        }
    }

    private static List<String> returnTypesFromBody(Map<String, Object> func) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Map<String, Object> bodyStmt : PyConverter.listOf(func, "body")) {
            if (!"Return".equals(PyConverter.typeOf(bodyStmt))) continue;
            Map<String, Object> value = PyConverter.mapOf(bodyStmt, "value");
            List<String> lit = literalType(value);
            if (!lit.isEmpty() && !lit.equals(List.of("callable"))) {
                out.addAll(lit);
            }
        }
        return List.copyOf(out);
    }

    private static void bindReturnedCallablesAndReceivers(
            Map<String, Object> pyModule,
            Map<String, List<String>> functionReturns,
            Map<String, List<String>> methodReturns,
            Map<String, List<String>> callReturns,
            Map<String, String> receiverTypes,
            Map<String, List<String>> callResults) {
        if (pyModule == null) return;
        walkAssignsForDataFlow(
                pyModule, functionReturns, methodReturns, callReturns, receiverTypes, callResults);
    }

    private static void walkAssignsForDataFlow(
            Map<String, Object> node,
            Map<String, List<String>> functionReturns,
            Map<String, List<String>> methodReturns,
            Map<String, List<String>> callReturns,
            Map<String, String> receiverTypes,
            Map<String, List<String>> callResults) {
        if (node == null || node.isEmpty()) return;
        String t = PyConverter.typeOf(node);
        // Preserve program order for Module / function / class bodies.
        if ("Module".equals(t) || "FunctionDef".equals(t) || "AsyncFunctionDef".equals(t)
                || "ClassDef".equals(t)) {
            for (Map<String, Object> stmt : PyConverter.listOf(node, "body")) {
                walkAssignsForDataFlow(stmt, functionReturns, methodReturns,
                        callReturns, receiverTypes, callResults);
            }
            return;
        }
        if ("Assign".equals(t)) {
            Map<String, Object> value = PyConverter.mapOf(node, "value");
            for (Map<String, Object> target : PyConverter.listOf(node, "targets")) {
                if (!"Name".equals(PyConverter.typeOf(target))) continue;
                String var = PyConverter.strOf(target, "id");
                if (var == null || value == null) continue;
                bindOneAssign(var, value, functionReturns, methodReturns,
                        callReturns, receiverTypes, callResults);
            }
            return;
        }
        if ("If".equals(t) || "For".equals(t) || "While".equals(t) || "With".equals(t)) {
            for (Map<String, Object> stmt : PyConverter.listOf(node, "body")) {
                walkAssignsForDataFlow(stmt, functionReturns, methodReturns,
                        callReturns, receiverTypes, callResults);
            }
            for (Map<String, Object> stmt : PyConverter.listOf(node, "orelse")) {
                walkAssignsForDataFlow(stmt, functionReturns, methodReturns,
                        callReturns, receiverTypes, callResults);
            }
        }
    }

    private static void bindOneAssign(String var,
                                      Map<String, Object> value,
                                      Map<String, List<String>> functionReturns,
                                      Map<String, List<String>> methodReturns,
                                      Map<String, List<String>> callReturns,
                                      Map<String, String> receiverTypes,
                                      Map<String, List<String>> callResults) {
        String t = PyConverter.typeOf(value);
        if ("Name".equals(t)) {
            String id = PyConverter.strOf(value, "id");
            List<String> fr = functionReturns.get(id);
            if (fr != null) {
                callReturns.put(var, fr);
            }
            List<String> viaReturned = functionReturns.get(id + "()");
            if (viaReturned != null) {
                callReturns.put(var + "()", viaReturned);
            }
            return;
        }
        if (!"Call".equals(t)) return;
        Map<String, Object> func = PyConverter.mapOf(value, "func");
        if ("Name".equals(PyConverter.typeOf(func))) {
            String callee = PyConverter.strOf(func, "id");
            if (callee != null && !callee.isEmpty() && Character.isUpperCase(callee.charAt(0))) {
                receiverTypes.put(var, callee);
                callResults.put(var, List.of(callee));
                return;
            }
            // Binder / import previously recorded a return type for callee.
            List<String> viaBinder = callReturns.get(callee);
            List<String> deeper = functionReturns.get(callee + "()");
            if (deeper == null) {
                for (Map.Entry<String, List<String>> e : functionReturns.entrySet()) {
                    if (e.getKey().endsWith("." + callee + "()") || e.getKey().equals(callee + "()")) {
                        deeper = e.getValue();
                        break;
                    }
                }
            }
            if (viaBinder != null && !viaBinder.isEmpty()) {
                callResults.put(var, viaBinder);
                // Factory: func returns callable, func()() yields concrete (imported_call).
                if (deeper != null && !deeper.isEmpty()) {
                    callReturns.put(var, deeper);
                }
                return;
            }
            List<String> fr = functionReturns.get(callee);
            if (fr != null && !fr.isEmpty()) {
                callResults.put(var, fr);
            }
            if (deeper != null) {
                callReturns.put(var, deeper);
            }
            return;
        }
        if ("Attribute".equals(PyConverter.typeOf(func))) {
            String attr = PyConverter.strOf(func, "attr");
            Map<String, Object> recv = PyConverter.mapOf(func, "value");
            if (!"Name".equals(PyConverter.typeOf(recv)) || attr == null) return;
            String recvName = PyConverter.strOf(recv, "id");
            // imported.A() — seed receiver for method lookup; leave callResults to the
            // exporter so it can emit qualified mod.Class tokens (not bare Class).
            if (!attr.isEmpty() && Character.isUpperCase(attr.charAt(0))) {
                receiverTypes.put(var, attr);
                return;
            }
            String className = receiverTypes.get(recvName);
            if (className == null) return;
            String q = className + "." + attr;
            List<String> ret = methodReturns.get(q);
            if (ret == null) ret = functionReturns.get(q);
            if (ret == null) ret = functionReturns.get(attr);
            if (ret != null && !ret.isEmpty()) {
                callResults.put(var, ret);
            }
        }
    }

    // ── Rule 5+6: self.attr delegation + import aliases ───────────────────────

    static Map<String, String> collectImportAliases(Map<String, Object> pyModule) {
        Map<String, String> aliases = new LinkedHashMap<>();
        if (pyModule == null) return aliases;
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            String t = PyConverter.typeOf(stmt);
            if ("Import".equals(t)) {
                for (Map<String, Object> alias : PyConverter.listOf(stmt, "names")) {
                    String name = PyConverter.strOf(alias, "name");
                    String as = PyConverter.strOf(alias, "asname");
                    if (name == null) continue;
                    aliases.put(as != null ? as : name, name);
                }
            } else if ("ImportFrom".equals(t)) {
                String mod = PyConverter.strOf(stmt, "module");
                for (Map<String, Object> alias : PyConverter.listOf(stmt, "names")) {
                    String name = PyConverter.strOf(alias, "name");
                    String as = PyConverter.strOf(alias, "asname");
                    if (name == null) continue;
                    String local = as != null ? as : name;
                    if (mod != null && !mod.isBlank()) {
                        aliases.put(local, mod + "." + name);
                    } else {
                        aliases.put(local, name);
                    }
                }
            }
        }
        return aliases;
    }

    static Map<String, Map<String, String>> collectAttrMethodBindings(Map<String, Object> pyModule) {
        Map<String, Map<String, String>> bindings = new LinkedHashMap<>();
        if (pyModule == null) return bindings;
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            if (!"ClassDef".equals(PyConverter.typeOf(stmt))) continue;
            String cls = PyConverter.strOf(stmt, "name");
            Map<String, String> map = new LinkedHashMap<>();
            for (Map<String, Object> body : PyConverter.listOf(stmt, "body")) {
                if (!"FunctionDef".equals(PyConverter.typeOf(body))) continue;
                for (Map<String, Object> b2 : PyConverter.listOf(body, "body")) {
                    if (!"Assign".equals(PyConverter.typeOf(b2))) continue;
                    Map<String, Object> value = PyConverter.mapOf(b2, "value");
                    for (Map<String, Object> target : PyConverter.listOf(b2, "targets")) {
                        if (!"Attribute".equals(PyConverter.typeOf(target))) continue;
                        if (!"self".equals(PyConverter.strOf(PyConverter.mapOf(target, "value"), "id"))) {
                            continue;
                        }
                        if (!"Attribute".equals(PyConverter.typeOf(value))) continue;
                        if (!"self".equals(PyConverter.strOf(PyConverter.mapOf(value, "value"), "id"))) {
                            continue;
                        }
                        String attr = PyConverter.strOf(target, "attr");
                        String method = PyConverter.strOf(value, "attr");
                        if (attr != null && method != null) map.put(attr, method);
                    }
                }
            }
            if (!map.isEmpty()) bindings.put(cls, map);
        }
        return bindings;
    }

    private static Map<String, List<String>> collectClassAttrLiterals(Map<String, Object> pyModule) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (pyModule == null) return out;
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            if (!"ClassDef".equals(PyConverter.typeOf(stmt))) continue;
            String cls = PyConverter.strOf(stmt, "name");
            for (Map<String, Object> body : PyConverter.listOf(stmt, "body")) {
                if ("Assign".equals(PyConverter.typeOf(body))) {
                    Map<String, Object> value = PyConverter.mapOf(body, "value");
                    List<String> types = literalType(value);
                    if (types.isEmpty() || types.equals(List.of("callable"))) continue;
                    for (Map<String, Object> target : PyConverter.listOf(body, "targets")) {
                        if (!"Name".equals(PyConverter.typeOf(target))) continue;
                        String attr = PyConverter.strOf(target, "id");
                        if (attr != null) out.put(cls + "." + attr, types);
                    }
                } else if ("FunctionDef".equals(PyConverter.typeOf(body))
                        || "AsyncFunctionDef".equals(PyConverter.typeOf(body))) {
                    // self.attr = literal inside methods (typically __init__)
                    for (Map<String, Object> mstmt : PyConverter.listOf(body, "body")) {
                        if (!"Assign".equals(PyConverter.typeOf(mstmt))) continue;
                        Map<String, Object> value = PyConverter.mapOf(mstmt, "value");
                        List<String> types = literalType(value);
                        if (types.isEmpty() || types.equals(List.of("callable"))) continue;
                        for (Map<String, Object> target : PyConverter.listOf(mstmt, "targets")) {
                            if (!"Attribute".equals(PyConverter.typeOf(target))) continue;
                            Map<String, Object> recv = PyConverter.mapOf(target, "value");
                            if (!"Name".equals(PyConverter.typeOf(recv))) continue;
                            String recvId = PyConverter.strOf(recv, "id");
                            if (!"self".equals(recvId) && !"cls".equals(recvId)) continue;
                            String attr = PyConverter.strOf(target, "attr");
                            if (attr != null) out.putIfAbsent(cls + "." + attr, types);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void resolveSelfAttrDelegation(
            Map<String, Object> pyModule,
            Map<String, Map<String, String>> bindings,
            Map<String, List<String>> methodReturns,
            Map<String, List<String>> functionReturns,
            Map<String, List<String>> classAttrs) {
        if (pyModule == null) return;
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            if (!"ClassDef".equals(PyConverter.typeOf(stmt))) continue;
            String cls = PyConverter.strOf(stmt, "name");
            for (Map<String, Object> body : PyConverter.listOf(stmt, "body")) {
                if (!"FunctionDef".equals(PyConverter.typeOf(body))) continue;
                String method = PyConverter.strOf(body, "name");
                String qname = cls + "." + method;
                List<String> cur = methodReturns.get(qname);
                if (cur != null && !cur.isEmpty() && !cur.equals(List.of("callable"))) {
                    continue;
                }
                List<String> inferred =
                        inferDelegatedReturn(body, cls, bindings, methodReturns, functionReturns, classAttrs);
                if (inferred.isEmpty()) continue;
                methodReturns.put(qname, inferred);
                functionReturns.put(qname, inferred);
                functionReturns.putIfAbsent(method, inferred);
            }
        }
    }

    private static List<String> inferDelegatedReturn(
            Map<String, Object> func,
            String cls,
            Map<String, Map<String, String>> bindings,
            Map<String, List<String>> methodReturns,
            Map<String, List<String>> functionReturns,
            Map<String, List<String>> classAttrs) {
        for (Map<String, Object> stmt : PyConverter.listOf(func, "body")) {
            if (!"Return".equals(PyConverter.typeOf(stmt))) continue;
            Map<String, Object> value = PyConverter.mapOf(stmt, "value");
            if ("Attribute".equals(PyConverter.typeOf(value))) {
                if ("self".equals(PyConverter.strOf(PyConverter.mapOf(value, "value"), "id"))) {
                    String attr = PyConverter.strOf(value, "attr");
                    // return self.method — method reference
                    if (methodReturns.containsKey(cls + "." + attr)
                            || functionReturns.containsKey(cls + "." + attr)) {
                        return List.of("callable");
                    }
                    List<String> attrType = classAttrs.get(cls + "." + attr);
                    if (attrType != null && !attrType.isEmpty()) return attrType;
                    return List.of("callable");
                }
            }
            if ("Call".equals(PyConverter.typeOf(value))) {
                Map<String, Object> callee = PyConverter.mapOf(value, "func");
                if ("Attribute".equals(PyConverter.typeOf(callee))
                        && "self".equals(PyConverter.strOf(PyConverter.mapOf(callee, "value"), "id"))) {
                    String attr = PyConverter.strOf(callee, "attr");
                    List<String> direct = methodReturns.get(cls + "." + attr);
                    if (direct == null) direct = functionReturns.get(cls + "." + attr);
                    if (direct != null && !direct.isEmpty() && !direct.equals(List.of("callable"))) {
                        return direct;
                    }
                    for (Map.Entry<String, Map<String, String>> e : bindings.entrySet()) {
                        String bound = e.getValue().get(attr);
                        if (bound == null) continue;
                        List<String> t = methodReturns.get(e.getKey() + "." + bound);
                        if (t == null) t = functionReturns.get(e.getKey() + "." + bound);
                        if (t == null) t = functionReturns.get(bound);
                        if (t != null && !t.isEmpty() && !t.equals(List.of("callable"))) {
                            return t;
                        }
                    }
                }
            }
        }
        return List.of();
    }

    private static void indexImportedFunctionReturns(
            Map<String, String> moduleSources,
            Path sourcePath,
            Map<String, String> importAliases,
            Map<String, List<String>> functionReturns,
            Map<String, List<String>> methodReturns,
            Map<String, List<String>> classAttrs) {
        if (importAliases == null || importAliases.isEmpty()) return;
        Set<String> neededMods = new LinkedHashSet<>();
        for (String qual : importAliases.values()) {
            neededMods.add(qual);
            int dot = qual.lastIndexOf('.');
            if (dot > 0) neededMods.add(qual.substring(0, dot));
        }
        Map<String, String> sources = new LinkedHashMap<>();
        if (moduleSources != null) sources.putAll(moduleSources);
        if (sourcePath != null && sourcePath.getParent() != null) {
            Path dir = sourcePath.getParent();
            for (String mod : neededMods) {
                if (sources.containsKey(mod)) continue;
                Path py = dir.resolve(mod.replace('.', '/') + ".py");
                if (!java.nio.file.Files.isRegularFile(py) && mod.contains(".")) {
                    py = dir.resolve(mod.substring(0, mod.indexOf('.')) + ".py");
                }
                if (java.nio.file.Files.isRegularFile(py)) {
                    try {
                        String text = java.nio.file.Files.readString(py);
                        sources.put(mod, text);
                        if (mod.contains(".")) {
                            sources.putIfAbsent(mod.substring(0, mod.indexOf('.')), text);
                        }
                    } catch (Exception ignored) {
                        // best-effort
                    }
                }
            }
        }
        if (sources.isEmpty()) return;
        PythonAstBridge bridge = new PythonAstBridge();
        // Also pull modules imported by already-loaded foreign sources (one hop).
        Set<String> extra = new LinkedHashSet<>();
        for (String mod : new ArrayList<>(neededMods)) {
            String src = sources.get(mod);
            if (src == null) continue;
            try {
                Map<String, Object> foreignAst = bridge.parse(src);
                for (String dep : collectImportAliases(foreignAst).values()) {
                    if (sources.containsKey(dep)) continue;
                    extra.add(dep);
                    int dot = dep.lastIndexOf('.');
                    if (dot > 0) extra.add(dep.substring(0, dot));
                }
            } catch (Exception ignored) {
                // best-effort
            }
        }
        if (sourcePath != null && sourcePath.getParent() != null) {
            Path dir = sourcePath.getParent();
            for (String mod : extra) {
                if (sources.containsKey(mod)) continue;
                Path py = dir.resolve(mod.replace('.', '/') + ".py");
                if (!java.nio.file.Files.isRegularFile(py) && mod.contains(".")) {
                    py = dir.resolve(mod.substring(0, mod.indexOf('.')) + ".py");
                }
                if (java.nio.file.Files.isRegularFile(py)) {
                    try {
                        String text = java.nio.file.Files.readString(py);
                        sources.put(mod, text);
                        String base = mod.contains(".") ? mod.substring(0, mod.indexOf('.')) : mod;
                        sources.putIfAbsent(base, text);
                        neededMods.add(base);
                        neededMods.add(mod);
                    } catch (Exception ignored) {
                        // best-effort
                    }
                }
            }
        }
        for (String mod : neededMods) {
            String src = sources.get(mod);
            if (src == null) continue;
            try {
                Map<String, Object> foreignAst = bridge.parse(src);
                Map<String, List<String>> fr = new LinkedHashMap<>();
                Map<String, List<String>> mr = new LinkedHashMap<>();
                indexFunctionReturns(foreignAst, fr, mr);
                for (Map.Entry<String, List<String>> e : fr.entrySet()) {
                    functionReturns.putIfAbsent(mod + "." + e.getKey(), e.getValue());
                    functionReturns.putIfAbsent(e.getKey(), e.getValue());
                }
                for (Map.Entry<String, List<String>> e : mr.entrySet()) {
                    methodReturns.putIfAbsent(e.getKey(), e.getValue());
                    methodReturns.putIfAbsent(mod + "." + e.getKey(), e.getValue());
                    functionReturns.putIfAbsent(mod + "." + e.getKey(), e.getValue());
                    functionReturns.putIfAbsent(e.getKey(), e.getValue());
                }
                if (classAttrs != null) {
                    for (Map.Entry<String, List<String>> e : collectClassAttrLiterals(foreignAst).entrySet()) {
                        classAttrs.putIfAbsent(e.getKey(), e.getValue());
                        classAttrs.putIfAbsent(mod + "." + e.getKey(), e.getValue());
                    }
                }
                // Resolve return self.attr → attr literal types on imported classes.
                Map<String, Map<String, String>> foreignBindings = collectAttrMethodBindings(foreignAst);
                resolveSelfAttrDelegation(foreignAst, foreignBindings, methodReturns, functionReturns,
                        classAttrs != null ? classAttrs : new LinkedHashMap<>());
            } catch (Exception ignored) {
                // best-effort import refine only
            }
        }
        // Second pass: resolve {@code return other_func} using hop-loaded callees.
        for (String mod : neededMods) {
            String src = sources.get(mod);
            if (src == null) continue;
            try {
                Map<String, Object> foreignAst = bridge.parse(src);
                // Apply this module's import aliases so {@code return return_func} resolves.
                applyImportAliasesToLocalBinders(
                        collectImportAliases(foreignAst), functionReturns, new LinkedHashMap<>());
                for (Map<String, Object> stmt : PyConverter.listOf(foreignAst, "body")) {
                    resolveReturnedNameReturns(stmt, null, functionReturns, methodReturns);
                }
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    private static void applyImportAliasesToLocalBinders(
            Map<String, String> importAliases,
            Map<String, List<String>> functionReturns,
            Map<String, List<String>> callReturns) {
        for (Map.Entry<String, String> e : importAliases.entrySet()) {
            String local = e.getKey();
            String qual = e.getValue();
            List<String> ret = functionReturns.get(qual);
            if (ret == null) {
                int dot = qual.lastIndexOf('.');
                if (dot > 0) ret = functionReturns.get(qual.substring(dot + 1));
            }
            if (ret == null || ret.isEmpty()) continue;
            functionReturns.putIfAbsent(local, ret);
            callReturns.putIfAbsent(local, ret);
        }
    }

    // ── shared literal / util ─────────────────────────────────────────────────

    static List<String> literalType(Map<String, Object> node) {
        if (node == null) return List.of();
        String t = PyConverter.typeOf(node);
        if ("Constant".equals(t)) {
            Object v = node.get("value");
            if (v instanceof Integer || v instanceof Long) return List.of("int");
            if (v instanceof Double || v instanceof Float) return List.of("float");
            if (v instanceof Boolean) return List.of("bool");
            if (v instanceof String) return List.of("str");
            if (v == null) return List.of("Nonetype");
        }
        if ("UnaryOp".equals(t)) {
            String op = PyConverter.typeOf(PyConverter.mapOf(node, "op"));
            List<String> inner = literalType(PyConverter.mapOf(node, "operand"));
            if (("USub".equals(op) || "UAdd".equals(op)) && !inner.isEmpty()) return inner;
        }
        if ("List".equals(t) || "ListComp".equals(t)) return List.of("list");
        if ("Dict".equals(t) || "DictComp".equals(t)) return List.of("dict");
        if ("Tuple".equals(t)) return List.of("tuple");
        if ("Set".equals(t) || "SetComp".equals(t)) return List.of("set");
        if ("Call".equals(t)) {
            Map<String, Object> func = PyConverter.mapOf(node, "func");
            if ("Name".equals(PyConverter.typeOf(func))) {
                String n = PyConverter.strOf(func, "id");
                if ("set".equals(n)) return List.of("set");
                if ("dict".equals(n)) return List.of("dict");
                if ("list".equals(n)) return List.of("list");
                if ("tuple".equals(n)) return List.of("tuple");
                if (n != null && !n.isEmpty() && Character.isUpperCase(n.charAt(0))) {
                    return List.of(n);
                }
            }
            return List.of("callable");
        }
        if ("Name".equals(t) || "Attribute".equals(t) || "Lambda".equals(t)) {
            return List.of("callable");
        }
        return List.of();
    }

    private static String constantIndexSite(String container, Map<String, Object> keyNode) {
        if (container == null || keyNode == null) return null;
        if (!"Constant".equals(PyConverter.typeOf(keyNode))) return null;
        Object v = keyNode.get("value");
        if (v instanceof String s) return container + "['" + s + "']";
        if (v instanceof Integer || v instanceof Long) return container + "[" + v + "]";
        return null;
    }

    static List<String> unionTypes(List<String> a, List<String> b) {
        if (a == null || a.isEmpty()) return b == null ? List.of() : b;
        if (b == null || b.isEmpty()) return a;
        Set<String> set = new LinkedHashSet<>(a);
        set.addAll(b);
        return List.copyOf(set);
    }
}
