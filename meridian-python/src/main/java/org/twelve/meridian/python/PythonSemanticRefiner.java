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
 * <p>Phase-1 rules:
 * <ol>
 *   <li>Call-site argument → parameter type constraints</li>
 *   <li>Container literal element projection ({@code d['foo']}, {@code xs[0]})</li>
 * </ol>
 */
public final class PythonSemanticRefiner {

    public PythonInferenceResult refine(AST gcpAst, Map<String, Object> pyAst,
                                        String fileName, Path sourcePath) {
        Map<String, List<List<String>>> callSiteArgTypes = collectCallArgTypes(pyAst);
        Map<String, List<String>> containerElements = new LinkedHashMap<>();
        Map<String, List<String>> callReturns = new LinkedHashMap<>();
        projectContainerLiterals(pyAst, containerElements, callReturns);
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
                refinedParams);
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
            t = literalType(arg);
            if (t.equals(List.of("callable"))) {
                return List.of();
            }
            return t;
        }
        return List.of();
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
                                                 Map<String, List<String>> callReturns) {
        if (pyModule == null) return;
        walkAssigns(pyModule, elements, callReturns);
    }

    private static void walkAssigns(Map<String, Object> node,
                                    Map<String, List<String>> elements,
                                    Map<String, List<String>> callReturns) {
        if (node == null || node.isEmpty()) return;
        if ("Assign".equals(PyConverter.typeOf(node))) {
            Map<String, Object> value = PyConverter.mapOf(node, "value");
            for (Map<String, Object> target : PyConverter.listOf(node, "targets")) {
                projectNameBinding(target, value, elements, callReturns);
            }
        }
        for (Object v : node.values()) {
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) m;
                walkAssigns(child, elements, callReturns);
            } else if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> child = (Map<String, Object>) m;
                        walkAssigns(child, elements, callReturns);
                    }
                }
            }
        }
    }

    private static void projectNameBinding(Map<String, Object> target,
                                           Map<String, Object> value,
                                           Map<String, List<String>> elements,
                                           Map<String, List<String>> callReturns) {
        if (!"Name".equals(PyConverter.typeOf(target))) return;
        String name = PyConverter.strOf(target, "id");
        if (name == null || value == null) return;

        if ("List".equals(PyConverter.typeOf(value))) {
            List<Map<String, Object>> elts = PyConverter.listOf(value, "elts");
            for (int i = 0; i < elts.size(); i++) {
                Map<String, Object> elt = elts.get(i);
                List<String> et = literalType(elt);
                if (et.isEmpty() && "Name".equals(PyConverter.typeOf(elt))) {
                    et = List.of("callable");
                    String id = PyConverter.strOf(elt, "id");
                    if (id != null) callReturns.put(name + "[" + i + "]", List.of());
                }
                if (et.isEmpty() || et.equals(List.of("Any"))) continue;
                elements.put(name + "[" + i + "]", et);
            }
        } else if ("Dict".equals(PyConverter.typeOf(value))) {
            projectDictElements(name, value, elements, callReturns);
        }
    }

    private static void projectDictElements(String dictName,
                                            Map<String, Object> dictLit,
                                            Map<String, List<String>> elements,
                                            Map<String, List<String>> callReturns) {
        List<Map<String, Object>> keys = PyConverter.listOf(dictLit, "keys");
        List<Map<String, Object>> values = PyConverter.listOf(dictLit, "values");
        int n = Math.min(keys.size(), values.size());
        for (int i = 0; i < n; i++) {
            Map<String, Object> key = keys.get(i);
            Map<String, Object> val = values.get(i);
            if (key == null) continue; // **unpack
            String path = constantIndexSite(dictName, key);
            if (path == null) continue;
            List<String> et = literalType(val);
            if (et.isEmpty() && "Name".equals(PyConverter.typeOf(val))) {
                et = List.of("callable");
                String id = PyConverter.strOf(val, "id");
                if (id != null) callReturns.put(path, List.of());
            }
            if (et.isEmpty()) continue;
            elements.put(path, et);
            if ("Dict".equals(PyConverter.typeOf(val))) {
                projectDictElements(path, val, elements, callReturns);
            }
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
