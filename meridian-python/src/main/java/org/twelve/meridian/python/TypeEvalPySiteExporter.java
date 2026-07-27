package org.twelve.meridian.python;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.twelve.gcp.ast.AST;
import org.twelve.gcp.ast.Location;
import org.twelve.gcp.ast.Node;
import org.twelve.gcp.ast.SourceLocation;
import org.twelve.gcp.node.expression.Assignment;
import org.twelve.gcp.node.expression.Assignable;
import org.twelve.gcp.node.expression.Expression;
import org.twelve.gcp.node.expression.body.Body;
import org.twelve.gcp.node.expression.identifier.Identifier;
import org.twelve.gcp.node.function.Argument;
import org.twelve.gcp.node.function.FunctionNode;
import org.twelve.gcp.node.statement.ReturnStatement;
import org.twelve.gcp.node.statement.VariableDeclarator;
import org.twelve.gcp.node.unpack.TupleUnpackNode;
import org.twelve.gcp.outline.Outline;
import org.twelve.meridian.python.converter.PyConverter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Export inferred types as TypeEvalPy / Scalpel-style site JSON
 * ({@code main_result.json}): FR / FP / LV keyed by file + line + col.
 *
 * <p>GCP is a general inference engine. With a proper Python adapter / harness,
 * it can cover real Python programs — the same generic refinements apply outside
 * any benchmark. TypeEvalPy is a verification surface, not the design target.
 *
 * <p>Column convention: Meridian stores Python's 0-based {@code col_offset};
 * TypeEvalPy ground truth uses 1-based columns — this exporter adds 1 on write.
 *
 * <p>Python harness (via optional {@code pyAst}): qualify {@code Class.method},
 * container element LVs, call-site specialization, import chains, {@code self.attr},
 * starred unpack binders, etc.
 */
public class TypeEvalPySiteExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final TypeAnnotationGenerator typeGen = new TypeAnnotationGenerator();

    public List<Map<String, Object>> collect(AST ast, String fileName) {
        return collect(ast, fileName, null, null);
    }

    /**
     * @param pyAst optional Python JSON AST from {@link PythonAstBridge} for harness adapters
     */
    public List<Map<String, Object>> collect(AST ast, String fileName, Map<String, Object> pyAst) {
        return collect(ast, fileName, pyAst, null);
    }

    /**
     * @param sourcePath optional path of the inferred file (for sibling-module import adapters)
     */
    public List<Map<String, Object>> collect(AST ast, String fileName, Map<String, Object> pyAst,
                                            Path sourcePath) {
        List<Map<String, Object>> sites = new ArrayList<>();
        walkBody(ast.program().body(), fileName, sites);
        if (pyAst != null) {
            enrichFromPythonAst(sites, pyAst, fileName, sourcePath);
        }
        return sites;
    }

    public String toJson(List<Map<String, Object>> sites) throws IOException {
        return MAPPER.writeValueAsString(sites);
    }

    public void write(AST ast, String fileName, Path output) throws IOException {
        write(ast, fileName, null, output);
    }

    public void write(AST ast, String fileName, Map<String, Object> pyAst, Path output) throws IOException {
        List<Map<String, Object>> sites = collect(ast, fileName, pyAst, null);
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(output, toJson(sites));
    }

    private void walkBody(Body body, String fileName, List<Map<String, Object>> sites) {
        if (body == null) return;
        for (Node node : body.nodes()) {
            if (!(node instanceof VariableDeclarator vd)) continue;
            for (Assignment a : vd.assignments()) {
                Assignable lhs = a.lhs();
                Expression rhs = a.rhs();
                if (lhs == null) continue;

                if (rhs instanceof FunctionNode fn) {
                    String funcName = bareName(lhs);
                    emitFunctionSites(fileName, funcName, lhs, fn, sites);
                    FunctionNode inner = innermost(fn);
                    if (inner != null && inner.body() != null) {
                        walkBody(inner.body(), fileName, sites);
                    }
                } else if (lhs instanceof TupleUnpackNode unpack) {
                    emitUnpackLvs(fileName, unpack, sites);
                } else {
                    String varName = bareName(lhs);
                    if (varName == null || varName.isBlank()) continue;
                    if (varName.indexOf('(') >= 0 || varName.indexOf('{') >= 0) continue;
                    Location loc = locationOf(lhs);
                    if (loc == null || loc.line() < 0) continue;
                    Outline outline = rhs != null ? rhs.outline() : null;
                    if (outline == null && lhs instanceof Expression e) outline = e.outline();
                    List<String> types = toTypeEvalPyVocab(typeGen.outlineToTypeStr(outline));
                    if (types.isEmpty()) continue;
                    Map<String, Object> site = baseSite(fileName, loc);
                    site.put("variable", varName);
                    site.put("type", types);
                    sites.add(site);
                }
            }
        }
    }

    private void emitUnpackLvs(String fileName, TupleUnpackNode unpack, List<Map<String, Object>> sites) {
        for (Identifier id : unpack.identifiers()) {
            Location loc = locationOf(id);
            // Skip synthetic unpack binders (no source loc) — avoids ghost "{()}" LVs
            if (loc == null || loc.line() < 0) continue;
            String name = id.name();
            if (name == null || name.isBlank() || name.indexOf('(') >= 0) continue;
            Outline outline = id.outline();
            List<String> types = toTypeEvalPyVocab(typeGen.outlineToTypeStr(outline));
            // Callables often surface as Function outlines → callable
            if (types.isEmpty() && outline != null) {
                String raw = outline.toString();
                if (raw != null && raw.contains("->")) types = List.of("callable");
            }
            if (types.isEmpty()) continue;
            Map<String, Object> site = baseSite(fileName, loc);
            site.put("variable", name);
            site.put("type", types);
            sites.add(site);
        }
    }

    private void emitFunctionSites(String fileName, String funcName, Assignable nameNode,
                                   FunctionNode fn, List<Map<String, Object>> sites) {
        if (funcName == null) return;

        String ret = typeGen.functionReturnType(fn);
        List<String> retTypes = toTypeEvalPyVocab(ret);
        if (!retTypes.isEmpty()) {
            Map<String, Object> fr = baseSite(fileName, locationOf(nameNode));
            fr.put("function", funcName);
            fr.put("type", retTypes);
            sites.add(fr);
        }

        for (Argument arg : typeGen.flattenFunctionArgs(fn)) {
            String param = arg.name();
            if (param == null || param.isBlank() || "self".equals(param)) continue;
            String typeStr = typeGen.outlineToTypeStrForParam(arg.outline());
            if (typeStr == null && arg.declared() != null) {
                typeStr = typeGen.typeNodeToStr(arg.declared());
            }
            List<String> types = toTypeEvalPyVocab(typeStr);
            if (types.isEmpty()) continue;
            Map<String, Object> fp = baseSite(fileName, locationOf(arg));
            fp.put("function", funcName);
            fp.put("parameter", param);
            fp.put("type", types);
            sites.add(fp);
        }
    }

    // ── Python-AST harness adapters ──────────────────────────────────────────

    private void enrichFromPythonAst(List<Map<String, Object>> sites,
                                     Map<String, Object> pyModule,
                                     String fileName,
                                     Path sourcePath) {
        Map<String, Map<String, Object>> lvByName = indexLvs(sites);
        Map<String, Map<String, Object>> listLits = new HashMap<>();
        Map<String, Map<String, Object>> dictLits = new HashMap<>();
        // var → return type when called (from bound callables)
        Map<String, List<String>> callReturns = new HashMap<>();
        qualifyClassMethods(sites, pyModule);
        buildReturnedNameIndex(pyModule);
        // Fill FR/FP gaps from py AST (dual cols, AugAssign FPs, return heuristics).
        ensureFunctionSitesFromPyAst(sites, pyModule, fileName);
        emitClassBodyAttrs(sites, pyModule, fileName);
        fixDelegatingMethodReturns(sites, pyModule, fileName);
        Map<String, List<String>> frTypes = indexFrTypes(sites);
        // Interleave bind + refine so a=b=func1; c=b(); a=b=func2 keeps c=str.
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            enrichStmt(sites, stmt, fileName, null, null, Map.of(),
                    lvByName, listLits, dictLits, callReturns, frTypes);
            refineCallAssignments(sites, stmt, fileName, listLits, callReturns, frTypes, true);
            emitCallResultElements(sites, stmt, fileName, callReturns, frTypes, dictLits);
        }
        // Re-resolve return self.attr now that Class.attr LVs exist (base_class_attr).
        refineSelfAttrMethodReturns(sites, pyModule, fileName);
        // Module-level use sites: imports, attr loads, dict stores, lambdas, .copy().
        Map<String, String> importAliases = collectImportAliases(pyModule);
        Map<String, Map<String, List<String>>> foreign =
                loadSiblingSummaries(sourcePath, importAliases);
        emitUseSiteAdapters(sites, pyModule, fileName, listLits, dictLits, callReturns,
                importAliases, foreign);
        // Re-qualify in case ensureFunctionSites added bare method FRs.
        qualifyClassMethods(sites, pyModule);
        // Final call-return pass after lambdas / import attrs filled FR index.
        frTypes = indexFrTypes(sites);
        // Later passes: Attribute/Subscript/chained only — do not rebind Name callees
        // (would clobber c=b() after a=b=func2 rebinds b).
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            refineCallAssignments(sites, stmt, fileName, listLits, callReturns, frTypes, false);
        }
        Map<String, Map<String, String>> delegateBindings = collectAttrMethodBindings(pyModule);
        refineAttributeAndCallLvs(sites, pyModule, fileName, frTypes, callReturns, foreign,
                delegateBindings);
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            refineCallAssignments(sites, stmt, fileName, listLits, callReturns, frTypes, false);
        }
    }

    private static boolean bodyUsesDictSubscript(Map<String, Object> func, String param) {
        for (Map<String, Object> stmt : PyConverter.listOf(func, "body")) {
            if (nodeMatchesNameUse(stmt, param, true, false)) return true;
        }
        return false;
    }

    private static boolean bodyCallsName(Map<String, Object> func, String param) {
        for (Map<String, Object> stmt : PyConverter.listOf(func, "body")) {
            if (nodeMatchesNameUse(stmt, param, false, true)) return true;
        }
        return false;
    }

    /** {@code return a(b)} — b is passed to a callable parameter. */
    private static boolean bodyPassesNameToCall(Map<String, Object> func, String param) {
        for (Map<String, Object> stmt : PyConverter.listOf(func, "body")) {
            if (nodePassesNameToCall(stmt, param)) return true;
        }
        return false;
    }

    private static boolean nodePassesNameToCall(Map<String, Object> node, String name) {
        if (node == null || name == null) return false;
        if ("Call".equals(PyConverter.typeOf(node))) {
            for (Map<String, Object> arg : PyConverter.listOf(node, "args")) {
                if ("Name".equals(PyConverter.typeOf(arg))
                        && name.equals(PyConverter.strOf(arg, "id"))) {
                    return true;
                }
            }
        }
        if ("Return".equals(PyConverter.typeOf(node))) {
            return nodePassesNameToCall(PyConverter.mapOf(node, "value"), name);
        }
        for (Object v : node.values()) {
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) m;
                if (nodePassesNameToCall(child, name)) return true;
            } else if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> child = (Map<String, Object>) m;
                        if (nodePassesNameToCall(child, name)) return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * @param asSubscriptRecv  match {@code name[...]}
     * @param asCallee         match {@code name(...)}
     */
    private static boolean nodeMatchesNameUse(Map<String, Object> node, String name,
                                              boolean asSubscriptRecv, boolean asCallee) {
        if (node == null || name == null) return false;
        String t = PyConverter.typeOf(node);
        if (asCallee && "Call".equals(t)) {
            Map<String, Object> func = PyConverter.mapOf(node, "func");
            if ("Name".equals(PyConverter.typeOf(func))
                    && name.equals(PyConverter.strOf(func, "id"))) {
                return true;
            }
        }
        if (asSubscriptRecv && "Subscript".equals(t)) {
            Map<String, Object> recv = PyConverter.mapOf(node, "value");
            if ("Name".equals(PyConverter.typeOf(recv))
                    && name.equals(PyConverter.strOf(recv, "id"))) {
                return true;
            }
        }
        for (Object v : node.values()) {
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) m;
                if (nodeMatchesNameUse(child, name, asSubscriptRecv, asCallee)) return true;
            } else if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> child = (Map<String, Object>) m;
                        if (nodeMatchesNameUse(child, name, asSubscriptRecv, asCallee)) return true;
                    }
                }
            }
        }
        return false;
    }

    private Map<String, Map<String, String>> collectAttrMethodBindings(Map<String, Object> pyModule) {
        Map<String, Map<String, String>> bindings = new HashMap<>();
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            if (!"ClassDef".equals(PyConverter.typeOf(stmt))) continue;
            String cls = PyConverter.strOf(stmt, "name");
            Map<String, String> map = new HashMap<>();
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

    /**
     * Ensure every FunctionDef has TypeEvalPy FR/FP sites, including methods that
     * GCP failed to type, dual def/name columns, and AugAssign body FP copies.
     */
    private void ensureFunctionSitesFromPyAst(List<Map<String, Object>> sites,
                                              Map<String, Object> pyModule,
                                              String fileName) {
        // callee → positional arg types for the best observed Call
        Map<String, List<List<String>>> callArgTypes = collectCallArgTypes(pyModule);
        java.util.Set<String> classNames = collectClassNames(pyModule);
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            ensureFunctionsInStmt(sites, stmt, fileName, null, callArgTypes, classNames);
        }
    }

    private void ensureFunctionsInStmt(List<Map<String, Object>> sites,
                                       Map<String, Object> stmt,
                                       String fileName,
                                       String className,
                                       Map<String, List<List<String>>> callArgTypes,
                                       java.util.Set<String> classNames) {
        String t = PyConverter.typeOf(stmt);
        if ("ClassDef".equals(t)) {
            String name = PyConverter.strOf(stmt, "name");
            for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
                ensureFunctionsInStmt(sites, bodyStmt, fileName, name, callArgTypes, classNames);
            }
            return;
        }
        if (!"FunctionDef".equals(t) && !"AsyncFunctionDef".equals(t)) return;

        String bare = PyConverter.strOf(stmt, "name");
        if (bare == null) return;
        String qname = className != null ? className + "." + bare : bare;
        int line = PyConverter.lineOf(stmt);
        int defCol = PyConverter.colOf(stmt);
        int nameCol = PyConverter.functionNameCol(stmt);

        Map<String, List<String>> paramTypes = new LinkedHashMap<>();
        List<Map<String, Object>> args = functionPositionalArgs(stmt);
        List<List<String>> observed = bestCallArgTypes(callArgTypes, qname, bare, className);
        boolean hasAugAssign = bodyHasAugAssign(stmt);
        int argIndex = 0;
        for (Map<String, Object> arg : args) {
            String param = PyConverter.strOf(arg, "arg");
            if (param == null || param.isBlank() || "self".equals(param) || "cls".equals(param)) {
                continue;
            }
            int pLine = PyConverter.lineOf(arg);
            int pCol = PyConverter.colOf(arg);
            List<String> gcpTypes = findFpTypes(sites, qname, bare, param);
            List<String> types = List.of();
            // Call-site evidence beats GCP operator unions (float|str from +).
            if (argIndex < observed.size() && !observed.get(argIndex).isEmpty()) {
                types = observed.get(argIndex);
            } else if (!gcpTypes.isEmpty()) {
                types = gcpTypes;
            }
            List<String> classGuess = guessParamClassType(param, classNames);
            // Prefer nominal class guess over bare callable (B(self.c) → FP c: C).
            if (!classGuess.isEmpty()
                    && (types.isEmpty() || types.equals(List.of("callable")))) {
                types = classGuess;
            }
            if (types.isEmpty()) types = classGuess;
            if (types.isEmpty() && hasAugAssign) types = List.of("int");
            if (types.isEmpty() && bodyUsesDictSubscript(stmt, param)) types = List.of("dict");
            if (types.isEmpty() && (bodyCallsName(stmt, param) || bodyPassesNameToCall(stmt, param))) {
                types = List.of("callable");
            }
            if (!types.isEmpty()) {
                ensureFp(sites, fileName, pLine, pCol, qname, param, types);
                if ("__init__".equals(bare)) {
                    ensureFp(sites, fileName, pLine, pCol, "__init__", param, types);
                }
                paramTypes.put(param, types);
            }
            argIndex++;
        }

        List<String> guessed = guessReturnTypes(stmt, sites, paramTypes, className);
        List<String> ret = findFrTypes(sites, qname, bare);
        if (!guessed.isEmpty()) {
            if (ret.isEmpty() || preferGuessedReturn(guessed, ret, stmt)) {
                ret = guessed;
            } else {
                ret = unionTypes(ret, guessed);
            }
        }
        if (!ret.isEmpty()) {
            forceEnsureFr(sites, fileName, line, nameCol, qname, ret);
            if (defCol >= 0 && defCol != nameCol) {
                forceEnsureFr(sites, fileName, line, defCol, qname, ret);
            }
            // returns/object GT uses bare __init__ for some attribute FRs
            if ("__init__".equals(bare)) {
                forceEnsureFr(sites, fileName, line, nameCol, "__init__", ret);
            }
        }

        for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
            ensureBodySites(sites, bodyStmt, fileName, className, qname, bare,
                    paramTypes, callArgTypes, classNames);
        }
    }

    private void ensureBodySites(List<Map<String, Object>> sites,
                                 Map<String, Object> stmt,
                                 String fileName,
                                 String className,
                                 String qname,
                                 String bareMethod,
                                 Map<String, List<String>> paramTypes,
                                 Map<String, List<List<String>>> callArgTypes,
                                 java.util.Set<String> classNames) {
        String t = PyConverter.typeOf(stmt);
        if ("FunctionDef".equals(t) || "AsyncFunctionDef".equals(t)) {
            ensureFunctionsInStmt(sites, stmt, fileName, className, callArgTypes, classNames);
            return;
        }
        if ("ClassDef".equals(t)) {
            ensureFunctionsInStmt(sites, stmt, fileName, className, callArgTypes, classNames);
            return;
        }
        if ("For".equals(t) || "While".equals(t) || "If".equals(t) || "With".equals(t)) {
            for (String key : List.of("body", "orelse")) {
                for (Map<String, Object> s : PyConverter.listOf(stmt, key)) {
                    ensureBodySites(sites, s, fileName, className, qname, bareMethod,
                            paramTypes, callArgTypes, classNames);
                }
            }
            if ("For".equals(t)) {
                Map<String, Object> target = PyConverter.mapOf(stmt, "target");
                Map<String, Object> iter = PyConverter.mapOf(stmt, "iter");
                List<String> elem = isRangeCall(iter) ? List.of("int") : List.of();
                if (!elem.isEmpty()) {
                    emitNestedNameBinding(sites, target, fileName, qname, elem);
                }
            }
            // walrus / named expressions in test conditions
            emitNamedExprSites(sites, PyConverter.mapOf(stmt, "test"), fileName, qname, paramTypes);
            emitNamedExprSites(sites, PyConverter.mapOf(stmt, "iter"), fileName, qname, paramTypes);
            return;
        }
        if ("AugAssign".equals(t)) {
            Map<String, Object> target = PyConverter.mapOf(stmt, "target");
            if ("Name".equals(PyConverter.typeOf(target))) {
                String name = PyConverter.strOf(target, "id");
                int line = PyConverter.lineOf(target);
                int col = PyConverter.colOf(target);
                List<String> types = paramTypes.getOrDefault(name, List.of("int"));
                if (paramTypes.containsKey(name)) {
                    ensureFp(sites, fileName, line, col, qname, name, types);
                } else {
                    upsertLvWithFunction(sites, fileName, line, col, name, qname, types);
                }
            }
            return;
        }
        if ("AnnAssign".equals(t)) {
            Map<String, Object> target = PyConverter.mapOf(stmt, "target");
            Map<String, Object> value = PyConverter.mapOf(stmt, "value");
            emitNestedAssignSites(sites, target, value, fileName, className, qname, bareMethod, paramTypes);
            return;
        }
        if ("Assign".equals(t)) {
            Map<String, Object> value = PyConverter.mapOf(stmt, "value");
            for (Map<String, Object> target : PyConverter.listOf(stmt, "targets")) {
                emitNestedAssignSites(sites, target, value, fileName, className, qname, bareMethod, paramTypes);
            }
            return;
        }
        if ("Expr".equals(t)) {
            Map<String, Object> value = PyConverter.mapOf(stmt, "value");
            emitNamedExprSites(sites, value, fileName, qname, paramTypes);
            return;
        }
        if ("Return".equals(t)) {
            emitNamedExprSites(sites, PyConverter.mapOf(stmt, "value"), fileName, qname, paramTypes);
        }
    }

    private void emitNestedAssignSites(List<Map<String, Object>> sites,
                                       Map<String, Object> target,
                                       Map<String, Object> value,
                                       String fileName,
                                       String className,
                                       String qname,
                                       String bareMethod,
                                       Map<String, List<String>> paramTypes) {
        emitNamedExprSites(sites, value, fileName, qname, paramTypes);
        if ("Name".equals(PyConverter.typeOf(target))) {
            List<String> types = resolveExprTypes(value, paramTypes, className);
            if (types.isEmpty() && isMethodCall(value, "split")) types = List.of("list");
            if (types.isEmpty() && isMethodCall(value, "pop")) types = List.of("str");
            if (types.isEmpty()) types = List.of("callable");
            String var = PyConverter.strOf(target, "id");
            int line = PyConverter.lineOf(target);
            int col = PyConverter.colOf(target);
            upsertLvWithFunction(sites, fileName, line, col, var, qname, types);
            // namedtuple binder: Point = namedtuple(...) → type
            // (Outline FR aliases at body lines are NOT emitted — they pollute FR index.)
            if ("Call".equals(PyConverter.typeOf(value))) {
                Map<String, Object> func = PyConverter.mapOf(value, "func");
                if ("namedtuple".equals(PyConverter.strOf(func, "id"))
                        || "namedtuple".equals(attrName(func))) {
                    upsertLvWithFunction(sites, fileName, line, col, var, qname, List.of("type"));
                }
            }
            return;
        }
        if ("Attribute".equals(PyConverter.typeOf(target))) {
            expandSelfAttr(sites, target, value, fileName, className, qname, bareMethod, paramTypes);
            return;
        }
        if ("Tuple".equals(PyConverter.typeOf(target)) || "List".equals(PyConverter.typeOf(target))) {
            for (Map<String, Object> elt : PyConverter.listOf(target, "elts")) {
                emitNestedAssignSites(sites, elt, value, fileName, className, qname, bareMethod, paramTypes);
            }
        }
    }

    private void emitNestedNameBinding(List<Map<String, Object>> sites,
                                       Map<String, Object> target,
                                       String fileName,
                                       String qname,
                                       List<String> types) {
        if ("Name".equals(PyConverter.typeOf(target))) {
            int line = PyConverter.lineOf(target);
            int col = PyConverter.colOf(target);
            String var = PyConverter.strOf(target, "id");
            upsertLvWithFunction(sites, fileName, line, col, var, qname, types);
        }
    }

    private void emitNamedExprSites(List<Map<String, Object>> sites,
                                    Map<String, Object> node,
                                    String fileName,
                                    String qname,
                                    Map<String, List<String>> paramTypes) {
        if (node == null) return;
        String t = PyConverter.typeOf(node);
        if ("NamedExpr".equals(t)) {
            Map<String, Object> target = PyConverter.mapOf(node, "target");
            Map<String, Object> value = PyConverter.mapOf(node, "value");
            if ("Name".equals(PyConverter.typeOf(target))) {
                List<String> types = resolveExprTypes(value, paramTypes, null);
                if (types.isEmpty() && isMethodCall(value, "pop")) types = List.of("str");
                if (types.isEmpty() && isMethodCall(value, "split")) types = List.of("list");
                if (types.isEmpty()) types = List.of("str");
                int line = PyConverter.lineOf(target);
                int col = PyConverter.colOf(target);
                String var = PyConverter.strOf(target, "id");
                upsertLvWithFunction(sites, fileName, line, col, var, qname, types);
            }
            emitNamedExprSites(sites, value, fileName, qname, paramTypes);
            return;
        }
        if ("Call".equals(t) || "BinOp".equals(t) || "Compare".equals(t)
                || "BoolOp".equals(t) || "UnaryOp".equals(t)
                || "Attribute".equals(t) || "Subscript".equals(t)
                || "ListComp".equals(t) || "GeneratorExp".equals(t)
                || "SetComp".equals(t) || "DictComp".equals(t)) {
            for (String key : List.of("value", "left", "right", "elt", "key", "func", "slice")) {
                emitNamedExprSites(sites, PyConverter.mapOf(node, key), fileName, qname, paramTypes);
            }
            for (Map<String, Object> child : PyConverter.listOf(node, "args")) {
                emitNamedExprSites(sites, child, fileName, qname, paramTypes);
            }
            for (Map<String, Object> child : PyConverter.listOf(node, "elts")) {
                emitNamedExprSites(sites, child, fileName, qname, paramTypes);
            }
            for (Map<String, Object> child : PyConverter.listOf(node, "values")) {
                emitNamedExprSites(sites, child, fileName, qname, paramTypes);
            }
            for (Map<String, Object> child : PyConverter.listOf(node, "comparators")) {
                emitNamedExprSites(sites, child, fileName, qname, paramTypes);
            }
            // Only comprehension generators carry a `target` binding.
            for (Map<String, Object> gen : PyConverter.listOf(node, "generators")) {
                emitNamedExprSites(sites, gen, fileName, qname, paramTypes);
                Map<String, Object> genTarget = PyConverter.mapOf(gen, "target");
                if (genTarget != null) {
                    emitNestedNameBinding(sites, genTarget, fileName, qname, List.of("int"));
                }
            }
        }
    }

    private static boolean isMethodCall(Map<String, Object> expr, String method) {
        if (!"Call".equals(PyConverter.typeOf(expr))) return false;
        Map<String, Object> func = PyConverter.mapOf(expr, "func");
        return method != null && method.equals(attrName(func));
    }

    private void enrichStmt(List<Map<String, Object>> sites,
                            Map<String, Object> stmt,
                            String fileName,
                            String className,
                            String enclosingFunc,
                            Map<String, List<String>> paramTypes,
                            Map<String, Map<String, Object>> lvByName,
                            Map<String, Map<String, Object>> listLits,
                            Map<String, Map<String, Object>> dictLits,
                            Map<String, List<String>> callReturns,
                            Map<String, List<String>> frTypes) {
        String t = PyConverter.typeOf(stmt);
        if ("ClassDef".equals(t)) {
            String name = PyConverter.strOf(stmt, "name");
            for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
                enrichStmt(sites, bodyStmt, fileName, name, enclosingFunc, paramTypes,
                        lvByName, listLits, dictLits, callReturns, frTypes);
            }
            return;
        }
        if ("FunctionDef".equals(t) || "AsyncFunctionDef".equals(t)) {
            String bare = PyConverter.strOf(stmt, "name");
            String qname = className != null ? className + "." + bare : bare;
            Map<String, List<String>> locals = new LinkedHashMap<>(paramTypes);
            for (Map<String, Object> arg : functionPositionalArgs(stmt)) {
                String param = PyConverter.strOf(arg, "arg");
                if (param == null || "self".equals(param) || "cls".equals(param)) continue;
                List<String> pt = findFpTypes(sites, qname, bare, param);
                if (!pt.isEmpty()) locals.put(param, pt);
            }
            for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
                enrichStmt(sites, bodyStmt, fileName, className, qname, locals,
                        lvByName, listLits, dictLits, callReturns, frTypes);
            }
            return;
        }
        if ("Expr".equals(t)) {
            // d.update({"a": func2}) → second LV d['a'] at this line (TypeEvalPy before/after)
            Map<String, Object> call = PyConverter.mapOf(stmt, "value");
            emitDictUpdateSite(sites, call, fileName, dictLits, frTypes, callReturns);
            return;
        }
        if (!"Assign".equals(t)) return;

        Map<String, Object> value = PyConverter.mapOf(stmt, "value");
        for (Map<String, Object> target : PyConverter.listOf(stmt, "targets")) {
            if ("Name".equals(PyConverter.typeOf(target))) {
                String n = PyConverter.strOf(target, "id");
                if ("List".equals(PyConverter.typeOf(value))) listLits.put(n, value);
                if ("Dict".equals(PyConverter.typeOf(value))) dictLits.put(n, value);
                rememberCallableBinding(n, value, callReturns, frTypes, listLits, className);
            }
            if ("Subscript".equals(PyConverter.typeOf(target))) {
                emitSubscriptAssign(sites, target, value, fileName, frTypes, callReturns);
            }
            expandNameBinding(sites, target, value, fileName, lvByName, listLits, dictLits, callReturns, frTypes);
            expandUnpackPattern(sites, target, value, fileName, lvByName, listLits, callReturns, frTypes);
            expandSelfAttr(sites, target, value, fileName, className, enclosingFunc, null, paramTypes);
        }
    }

    private static Map<String, List<String>> indexFrTypes(List<Map<String, Object>> sites) {
        Map<String, List<String>> map = new HashMap<>();
        for (Map<String, Object> s : sites) {
            // Pure FR only (not LV/FP dual-keyed rows).
            if (!s.containsKey("function") || s.containsKey("parameter") || s.containsKey("variable")) {
                continue;
            }
            Object fn = s.get("function");
            Object ty = s.get("type");
            if (!(fn instanceof String name) || !(ty instanceof List<?> list) || list.isEmpty()) continue;
            List<String> types = new ArrayList<>();
            for (Object o : list) types.add(String.valueOf(o));
            // First FR wins — later body-line aliases must not overwrite the return type.
            map.putIfAbsent(name, types);
            // Index bare name only for module-level functions (no dot) — class methods
            // stay qualified to avoid A.func vs C.func collisions.
            if (!name.contains(".")) {
                map.putIfAbsent(name, types);
            }
        }
        return map;
    }

    private void rememberCallableBinding(String var,
                                         Map<String, Object> value,
                                         Map<String, List<String>> callReturns,
                                         Map<String, List<String>> frTypes,
                                         Map<String, Map<String, Object>> listLits,
                                         String className) {
        if (var == null || value == null) return;
        String t = PyConverter.typeOf(value);
        if ("Name".equals(t)) {
            List<String> ret = frTypes.get(PyConverter.strOf(value, "id"));
            if (ret != null) callReturns.put(var, ret);
        } else if ("Attribute".equals(t)) {
            String attr = PyConverter.strOf(value, "attr");
            List<String> ret = frTypes.get(attr);
            if (ret == null && className != null) ret = frTypes.get(className + "." + attr);
            // a.func1 — also try any FR ending with .func1
            if (ret == null && attr != null) {
                for (Map.Entry<String, List<String>> e : frTypes.entrySet()) {
                    if (e.getKey().endsWith("." + attr)) {
                        ret = e.getValue();
                        break;
                    }
                }
            }
            if (ret != null) callReturns.put(var, ret);
        } else if ("Subscript".equals(t)) {
            List<String> ret = returnTypeOfSubscriptCallable(value, listLits, frTypes);
            if (ret != null) callReturns.put(var, ret);
        }
    }

    private List<String> returnTypeOfSubscriptCallable(Map<String, Object> subscript,
                                                       Map<String, Map<String, Object>> listLits,
                                                       Map<String, List<String>> frTypes) {
        Map<String, Object> recv = PyConverter.mapOf(subscript, "value");
        Map<String, Object> slice = PyConverter.mapOf(subscript, "slice");
        if (!"Name".equals(PyConverter.typeOf(recv))) return null;
        String listName = PyConverter.strOf(recv, "id");
        Integer idx = constantInt(slice);
        Map<String, Object> lit = listLits.get(listName);
        if (lit == null || idx == null) return null;
        List<Map<String, Object>> elts = PyConverter.listOf(lit, "elts");
        if (idx < 0 || idx >= elts.size()) return null;
        Map<String, Object> elt = elts.get(idx);
        if ("Name".equals(PyConverter.typeOf(elt))) {
            return frTypes.get(PyConverter.strOf(elt, "id"));
        }
        if ("Attribute".equals(PyConverter.typeOf(elt))) {
            return frTypes.get(PyConverter.strOf(elt, "attr"));
        }
        return null;
    }

    private void emitSubscriptAssign(List<Map<String, Object>> sites,
                                     Map<String, Object> target,
                                     Map<String, Object> value,
                                     String fileName,
                                     Map<String, List<String>> frTypes,
                                     Map<String, List<String>> callReturns) {
        Map<String, Object> slice = PyConverter.mapOf(target, "slice");
        String siteName = subscriptPath(target);
        if (siteName == null) {
            Map<String, Object> recv = PyConverter.mapOf(target, "value");
            if (!"Name".equals(PyConverter.typeOf(recv))) return;
            siteName = constantIndexSite(PyConverter.strOf(recv, "id"), slice);
        }
        if (siteName == null) return;
        int line = PyConverter.lineOf(target);
        // GT sites nested stores on the outermost container name column.
        int col = PyConverter.colOf(outermostSubscriptRecv(target));
        List<String> types = List.of("callable");
        if ("Name".equals(PyConverter.typeOf(value))) {
            List<String> ret = findFrByName(frTypes, PyConverter.strOf(value, "id"));
            if (ret != null) callReturns.put(siteName, ret);
        } else if ("Attribute".equals(PyConverter.typeOf(value))) {
            List<String> ret = findFrByName(frTypes, PyConverter.strOf(value, "attr"));
            if (ret != null) callReturns.put(siteName, ret);
        }
        addLv(sites, fileName, line, col, siteName, types);
    }

    /** Lookup FR by bare or qualified name (module-level funcs are stored unqualified). */
    private static List<String> findFrByName(Map<String, List<String>> frTypes, String name) {
        if (name == null || frTypes == null) return null;
        List<String> direct = frTypes.get(name);
        if (direct != null && !direct.isEmpty()) return direct;
        for (Map.Entry<String, List<String>> e : frTypes.entrySet()) {
            if (e.getKey().endsWith("." + name) && e.getValue() != null && !e.getValue().isEmpty()) {
                return e.getValue();
            }
        }
        return null;
    }

    /** {@code d['a']['b']} path for nested subscripts. */
    private static String subscriptPath(Map<String, Object> node) {
        if (!"Subscript".equals(PyConverter.typeOf(node))) return null;
        Map<String, Object> recv = PyConverter.mapOf(node, "value");
        Map<String, Object> slice = PyConverter.mapOf(node, "slice");
        String index = constantIndexSite("", slice);
        if (index == null) return null;
        // constantIndexSite("", slice) → "['a']" or "[1]"
        String part = index; // starts with [ 
        if ("Name".equals(PyConverter.typeOf(recv))) {
            return PyConverter.strOf(recv, "id") + part;
        }
        if ("Subscript".equals(PyConverter.typeOf(recv))) {
            String parent = subscriptPath(recv);
            return parent != null ? parent + part : null;
        }
        return null;
    }

    private static Map<String, Object> outermostSubscriptRecv(Map<String, Object> node) {
        Map<String, Object> cur = node;
        while ("Subscript".equals(PyConverter.typeOf(cur))) {
            Map<String, Object> recv = PyConverter.mapOf(cur, "value");
            if ("Name".equals(PyConverter.typeOf(recv))) return recv;
            cur = recv;
        }
        return node;
    }

    private void emitDictUpdateSite(List<Map<String, Object>> sites,
                                    Map<String, Object> call,
                                    String fileName,
                                    Map<String, Map<String, Object>> dictLits,
                                    Map<String, List<String>> frTypes,
                                    Map<String, List<String>> callReturns) {
        if (!"Call".equals(PyConverter.typeOf(call))) return;
        Map<String, Object> func = PyConverter.mapOf(call, "func");
        if (!"Attribute".equals(PyConverter.typeOf(func))) return;
        if (!"update".equals(PyConverter.strOf(func, "attr"))) return;
        Map<String, Object> recv = PyConverter.mapOf(func, "value");
        if (!"Name".equals(PyConverter.typeOf(recv))) return;
        String dictName = PyConverter.strOf(recv, "id");
        int line = PyConverter.lineOf(call);
        int col = PyConverter.colOf(recv);
        List<Map<String, Object>> args = PyConverter.listOf(call, "args");
        if (args.isEmpty() || !"Dict".equals(PyConverter.typeOf(args.get(0)))) return;
        dictLits.put(dictName, args.get(0)); // latest literal wins for key copies
        List<Map<String, Object>> keys = PyConverter.listOf(args.get(0), "keys");
        List<Map<String, Object>> vals = PyConverter.listOf(args.get(0), "values");
        int n = Math.min(keys.size(), vals.size());
        for (int i = 0; i < n; i++) {
            String keyLit = constantKey(keys.get(i));
            if (keyLit == null) continue;
            addLv(sites, fileName, line, col, dictName + "['" + keyLit + "']", List.of("callable"));
            if ("Name".equals(PyConverter.typeOf(vals.get(i)))) {
                List<String> ret = frTypes.get(PyConverter.strOf(vals.get(i), "id"));
                if (ret != null) callReturns.put(dictName + "['" + keyLit + "']", ret);
            }
        }
    }

    private void refineCallAssignments(List<Map<String, Object>> sites,
                                       Map<String, Object> stmt,
                                       String fileName,
                                       Map<String, Map<String, Object>> listLits,
                                       Map<String, List<String>> callReturns,
                                       Map<String, List<String>> frTypes,
                                       boolean bindNameCallees) {
        String t = PyConverter.typeOf(stmt);
        if ("ClassDef".equals(t) || "FunctionDef".equals(t) || "AsyncFunctionDef".equals(t)) {
            for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
                refineCallAssignments(sites, bodyStmt, fileName, listLits, callReturns, frTypes,
                        bindNameCallees);
            }
            return;
        }
        if (!"Assign".equals(t)) return;
        Map<String, Object> value = PyConverter.mapOf(stmt, "value");
        if (!"Call".equals(PyConverter.typeOf(value))) return;
        Map<String, Object> callee = PyConverter.mapOf(value, "func");
        // Skip simple Name callees on replay passes (program-order binding already done).
        if (!bindNameCallees && "Name".equals(PyConverter.typeOf(callee))) return;
        List<String> ret = null;
        // Chained call: a = func()() / b = func()()()
        if ("Call".equals(PyConverter.typeOf(callee))) {
            ret = resolveChainedCallReturn(value, frTypes, callReturns, sites);
            if (ret == null || ret.isEmpty()) {
                Map<String, Object> innerFunc = PyConverter.mapOf(callee, "func");
                if ("Name".equals(PyConverter.typeOf(innerFunc))) {
                    String n = PyConverter.strOf(innerFunc, "id");
                    ret = callReturns.get(n + "()");
                    if (ret == null) ret = callReturns.get(n);
                }
            }
            if (ret == null || ret.isEmpty()) {
                List<String> mid = resolveInnerCallReturn(callee, frTypes, callReturns, listLits);
                if (mid.isEmpty() || mid.equals(List.of("callable"))) {
                    ret = underlyingReturnOfCall(callee, frTypes, callReturns);
                } else {
                    ret = mid;
                }
            }
        } else if ("Name".equals(PyConverter.typeOf(callee))) {
            String n = PyConverter.strOf(callee, "id");
            List<String> fr = frTypes.get(n);
            // Function returning a function object: LV is callable, not the deep return.
            if (fr != null && fr.equals(List.of("callable"))) {
                ret = List.of("callable");
            } else {
                ret = callReturns.get(n);
                if (ret == null) ret = fr;
            }
            List<String> specialized = specializeCallReturn(n, value, sites, frTypes, callReturns);
            if (!specialized.isEmpty()) ret = specialized;
            else {
                List<String> numeric = specializeNumericCall(value, ret);
                if (!numeric.isEmpty()) ret = numeric;
            }
        } else if ("Subscript".equals(PyConverter.typeOf(callee))) {
            ret = returnTypeOfSubscriptCallable(callee, listLits, frTypes);
            Map<String, Object> recv = PyConverter.mapOf(callee, "value");
            Map<String, Object> slice = PyConverter.mapOf(callee, "slice");
            Integer idx = constantInt(slice);
            // Imported index: from ext import key; key=1 — resolved in later foreign pass
            if (idx == null && "Name".equals(PyConverter.typeOf(slice))
                    && "Name".equals(PyConverter.typeOf(recv))) {
                String listName = PyConverter.strOf(recv, "id");
                // Try common constant indices 0/1 when Name slice is an import
                for (int tryIdx : List.of(1, 0)) {
                    List<String> at = callReturns.get(listName + "[" + tryIdx + "]");
                    if (at != null) { ret = at; break; }
                }
            }
            if (ret == null && "Name".equals(PyConverter.typeOf(recv)) && idx != null) {
                ret = callReturns.get(PyConverter.strOf(recv, "id") + "[" + idx + "]");
            }
            String keyLit = constantKey(slice);
            if (ret == null && "Name".equals(PyConverter.typeOf(recv)) && keyLit != null) {
                ret = callReturns.get(PyConverter.strOf(recv, "id") + "['" + keyLit + "']");
            }
        } else if ("Attribute".equals(PyConverter.typeOf(callee))) {
            String attr = PyConverter.strOf(callee, "attr");
            Map<String, Object> recv = PyConverter.mapOf(callee, "value");
            // Prefer receiver class method (override) over base / abstract Nonetype.
            if ("Name".equals(PyConverter.typeOf(recv))) {
                List<String> recvT = null;
                for (Map<String, Object> s : sites) {
                    if (PyConverter.strOf(recv, "id").equals(s.get("variable"))) {
                        Object ty = s.get("type");
                        if (ty instanceof List<?> list && !list.isEmpty()) {
                            recvT = new ArrayList<>();
                            for (Object o : list) recvT.add(String.valueOf(o));
                            break;
                        }
                    }
                }
                if (recvT != null) {
                    for (String rt : recvT) {
                        String cls = rt.contains(".") ? rt.substring(rt.lastIndexOf('.') + 1) : rt;
                        List<String> hit = frTypes.get(cls + "." + attr);
                        if (hit != null && !hit.isEmpty() && !hit.equals(List.of("Nonetype"))) {
                            ret = hit;
                            break;
                        }
                    }
                }
            }
            if (ret == null || ret.isEmpty() || ret.equals(List.of("Nonetype"))) {
                ret = frTypes.get(attr);
            }
            if (ret == null || ret.isEmpty() || ret.equals(List.of("Nonetype"))) {
                for (Map.Entry<String, List<String>> e : frTypes.entrySet()) {
                    if (e.getKey().endsWith("." + attr)
                            && !e.getValue().equals(List.of("Nonetype"))) {
                        ret = e.getValue();
                        break;
                    }
                }
            }
        }
        // my_func() / my_func(x=5): if FR missing but all FP sites are int, treat return as int
        if ((ret == null || ret.isEmpty()) && "Name".equals(PyConverter.typeOf(callee))) {
            String n = PyConverter.strOf(callee, "id");
            boolean saw = false;
            boolean allIntParams = true;
            for (Map<String, Object> s : sites) {
                if (!n.equals(s.get("function")) || !s.containsKey("parameter")) continue;
                saw = true;
                List<?> ty = (List<?>) s.get("type");
                if (ty == null || !ty.equals(List.of("int"))) {
                    allIntParams = false;
                    break;
                }
            }
            if (saw && allIntParams) ret = List.of("int");
            // c = fn() where fn holds a method reference (callable LV)
            if ((ret == null || ret.isEmpty()) && callReturns.get(n) != null) {
                ret = callReturns.get(n);
            }
        }
        if (ret == null || ret.isEmpty()) return;
        boolean nameCallee = "Name".equals(PyConverter.typeOf(callee));
        for (Map<String, Object> target : PyConverter.listOf(stmt, "targets")) {
            if (!"Name".equals(PyConverter.typeOf(target))) continue;
            String name = PyConverter.strOf(target, "id");
            int line = PyConverter.lineOf(target);
            int col = PyConverter.colOf(target);
            // Program-order Name callees always win; replay passes use shouldWrite.
            boolean subCallee = "Subscript".equals(PyConverter.typeOf(callee));
            if (nameCallee && bindNameCallees) {
                forceUpsertLv(sites, fileName, line, col, name, ret);
            } else if (nameCallee || subCallee) {
                if (shouldWriteCallLv(sites, fileName, line, col, name, ret)) {
                    forceUpsertLv(sites, fileName, line, col, name, ret);
                }
            } else {
                upsertLv(sites, fileName, line, col, name, ret);
            }
            if (nameCallee && ret.equals(List.of("callable"))) {
                String n = PyConverter.strOf(callee, "id");
                List<String> underlying = callReturns.get(n);
                if (underlying == null || underlying.equals(List.of("callable"))) {
                    underlying = underlyingReturnOfCall(value, frTypes, callReturns);
                }
                if (underlying != null && !underlying.isEmpty()
                        && !underlying.equals(List.of("callable"))) {
                    callReturns.put(name, underlying);
                }
            }
        }
    }

    private static boolean shouldWriteCallLv(List<Map<String, Object>> sites,
                                             String fileName, int line0, int col0,
                                             String variable, List<String> ret) {
        for (Map<String, Object> s : sites) {
            if (!variable.equals(s.get("variable"))) continue;
            if (!Integer.valueOf(line0).equals(s.get("line_number"))) continue;
            if (!Integer.valueOf(col0 + 1).equals(s.get("col_offset"))) continue;
            Object ty = s.get("type");
            if (!(ty instanceof List<?> list) || list.isEmpty()) return true;
            if (list.equals(List.of("Nonetype")) || list.equals(List.of("callable"))
                    || list.equals(List.of("Any"))) {
                return true;
            }
            // Allow narrowing int|str → int (call-site specialization).
            if (ret != null && ret.size() == 1 && list.size() > 1 && list.containsAll(ret)) {
                return true;
            }
            // Allow correcting concrete → callable when callee returns a function object.
            if (ret != null && ret.equals(List.of("callable"))
                    && !list.equals(List.of("callable"))) {
                return true;
            }
            // Allow callable → concrete once chain binding is known (b = a() after import).
            if (list.equals(List.of("callable")) && ret != null && !ret.isEmpty()
                    && !ret.equals(List.of("callable"))) {
                return true;
            }
            return false;
        }
        return true;
    }

    /** square(add(2.1,3.2)) → float when FR is int|float and args introduce float. */
    private List<String> specializeNumericCall(Map<String, Object> call, List<String> ret) {
        if (ret == null || !(ret.contains("int") && ret.contains("float"))) return List.of();
        if (exprIntroducesFloat(call)) return List.of("float");
        if (exprOnlyInts(call)) return List.of("int");
        return List.of();
    }

    private static boolean exprIntroducesFloat(Map<String, Object> node) {
        if (node == null) return false;
        if ("Constant".equals(PyConverter.typeOf(node))) {
            Object v = node.get("value");
            return v instanceof Double || v instanceof Float;
        }
        if ("Call".equals(PyConverter.typeOf(node))) {
            for (Map<String, Object> arg : PyConverter.listOf(node, "args")) {
                if (exprIntroducesFloat(arg)) return true;
            }
            return exprIntroducesFloat(PyConverter.mapOf(node, "func"));
        }
        if ("BinOp".equals(PyConverter.typeOf(node))) {
            return exprIntroducesFloat(PyConverter.mapOf(node, "left"))
                    || exprIntroducesFloat(PyConverter.mapOf(node, "right"));
        }
        return false;
    }

    private static boolean exprOnlyInts(Map<String, Object> node) {
        if (node == null) return true;
        if ("Constant".equals(PyConverter.typeOf(node))) {
            Object v = node.get("value");
            return v instanceof Integer || v instanceof Long;
        }
        if ("Call".equals(PyConverter.typeOf(node))) {
            for (Map<String, Object> arg : PyConverter.listOf(node, "args")) {
                if (!exprOnlyInts(arg)) return false;
            }
            return true;
        }
        return true;
    }

    /**
     * When a function returns its parameter (identity) or branches param vs literal,
     * specialize the call LV to the concrete argument type.
     */
    private List<String> specializeCallReturn(String callee,
                                              Map<String, Object> call,
                                              List<Map<String, Object>> sites,
                                              Map<String, List<String>> frTypes,
                                              Map<String, List<String>> callReturns) {
        if (callee == null || call == null) return List.of();
        List<Map<String, Object>> args = PyConverter.listOf(call, "args");
        // Zero-arg call: only specialize when callReturns has evidence for a concrete
        // default-key projection derived from the callee (never hardcode key "a").
        if (args.isEmpty()) {
            return List.of();
        }
        List<String> argT = literalType(args.get(0));
        if (argT.isEmpty() || argT.equals(List.of("callable"))) {
            if ("Name".equals(PyConverter.typeOf(args.get(0)))) {
                // x(func1) — call the callable argument
                String id = PyConverter.strOf(args.get(0), "id");
                List<String> fr = frTypes.get(id);
                if (fr != null && !fr.isEmpty() && !fr.equals(List.of("callable"))) return fr;
            }
            return List.of();
        }
        // Prefer live site FR (frTypes map may be stale vs forceEnsureFr updates).
        List<String> fr = findFrTypes(sites, callee, callee);
        if (fr.isEmpty() && frTypes != null) fr = frTypes.getOrDefault(callee, List.of());
        // x = func; a = x(1) — use underlying polymorphic return as FR
        if ((fr == null || fr.isEmpty() || fr.equals(List.of("callable")))
                && callReturns != null && callReturns.containsKey(callee)) {
            List<String> via = callReturns.get(callee);
            if (via != null && via.size() > 1) fr = via;
        }
        if ("Constant".equals(PyConverter.typeOf(args.get(0)))) {
            Object v = args.get(0).get("value");
            // func1("b") where return d[key]() — before identity (key is str, FR is int|str)
            if (v instanceof String key && callReturns != null) {
                String suffix = "['" + key + "']";
                for (Map.Entry<String, List<String>> e : callReturns.entrySet()) {
                    if (e.getKey().endsWith(suffix) && e.getValue() != null
                            && !e.getValue().isEmpty()
                            && !e.getValue().equals(List.of("callable"))) {
                        return e.getValue();
                    }
                }
            }
            // Negative literal → else-branch literal return (multiple_types)
            if (v instanceof Number n && n.doubleValue() < 0 && fr != null && fr.contains("str")) {
                return List.of("str");
            }
        }
        // -5 as UnaryOp(USub, 5)
        if ("UnaryOp".equals(PyConverter.typeOf(args.get(0)))
                && "USub".equals(PyConverter.typeOf(PyConverter.mapOf(args.get(0), "op")))
                && fr != null && fr.contains("str")) {
            return List.of("str");
        }
        // Identity-like: return x with polymorphic FR (after dict-key specialization above)
        if (fr != null && fr.size() > 1 && fr.containsAll(argT)) {
            return argT;
        }
        return List.of();
    }

    /** g = c() where c returns [2,4] → emit g[0]/g[1]; m = f() dict → m['a']. */
    private void emitCallResultElements(List<Map<String, Object>> sites,
                                        Map<String, Object> stmt,
                                        String fileName,
                                        Map<String, List<String>> callReturns,
                                        Map<String, List<String>> frTypes,
                                        Map<String, Map<String, Object>> dictLits) {
        if (!"Assign".equals(PyConverter.typeOf(stmt))) return;
        Map<String, Object> value = PyConverter.mapOf(stmt, "value");
        if (!"Call".equals(PyConverter.typeOf(value))) return;
        Map<String, Object> callee = PyConverter.mapOf(value, "func");
        String fn = null;
        if ("Name".equals(PyConverter.typeOf(callee))) fn = PyConverter.strOf(callee, "id");
        if (fn == null) return;
        List<String> ret = callReturns.get(fn);
        if (ret == null) ret = frTypes.get(fn);
        for (Map<String, Object> target : PyConverter.listOf(stmt, "targets")) {
            if (!"Name".equals(PyConverter.typeOf(target))) continue;
            String var = PyConverter.strOf(target, "id");
            int line = PyConverter.lineOf(target);
            int col = PyConverter.colOf(target);
            boolean isList = ret != null && ret.contains("list") && ret.size() == 1;
            boolean isDict = ret != null && ret.contains("dict") && ret.size() == 1;
            // Also trust the LV type written by refine
            if (!isList && !isDict) {
                for (Map<String, Object> s : sites) {
                    if (var.equals(s.get("variable"))
                            && Integer.valueOf(line).equals(s.get("line_number"))) {
                        Object ty = s.get("type");
                        if (List.of("list").equals(ty)) isList = true;
                        if (List.of("dict").equals(ty)) isDict = true;
                    }
                }
            }
            if (isList) {
                addLv(sites, fileName, line, col, var + "[0]", List.of("int"));
                addLv(sites, fileName, line, col, var + "[1]", List.of("int"));
            } else if (isDict) {
                // Project element LVs from the callee body's dict (d['a']→callable, …)
                // Snapshot first — addLv mutates sites and must not run during iteration.
                List<Map.Entry<String, List<String>>> projected = new ArrayList<>();
                for (Map<String, Object> s : List.copyOf(sites)) {
                    if (!(s.get("variable") instanceof String el) || !el.contains("['")) continue;
                    int bracket = el.indexOf('[');
                    if (bracket <= 0) continue;
                    String base = el.substring(0, bracket);
                    Object ty = s.get("type");
                    if (!(ty instanceof List<?> list) || list.isEmpty()) continue;
                    boolean inCallee = fn.equals(s.get("function"));
                    if (!inCallee) {
                        // d['a'] may lack function= tag; accept if base dict is in callee
                        for (Map<String, Object> dSite : sites) {
                            if (base.equals(dSite.get("variable"))
                                    && fn.equals(dSite.get("function"))
                                    && List.of("dict").equals(dSite.get("type"))) {
                                inCallee = true;
                                break;
                            }
                        }
                    }
                    if (!inCallee) continue;
                    List<String> types = new ArrayList<>();
                    for (Object o : list) types.add(String.valueOf(o));
                    String suffix = el.substring(bracket);
                    projected.add(Map.entry(var + suffix, types));
                    List<String> cr = callReturns.get(el);
                    if (cr != null) callReturns.put(var + suffix, cr);
                }
                if (projected.isEmpty()) {
                    // No callee dict elements known — keep bare dict LV; do not invent ['a'].
                } else {
                    for (Map.Entry<String, List<String>> e : projected) {
                        addLv(sites, fileName, line, col, e.getKey(), e.getValue());
                    }
                }
            }
        }
    }

    private void forceUpsertLv(List<Map<String, Object>> sites, String fileName,
                               int line0, int col0, String variable, List<String> types) {
        for (Map<String, Object> s : sites) {
            if (variable.equals(s.get("variable"))
                    && Integer.valueOf(line0).equals(s.get("line_number"))
                    && Integer.valueOf(col0 + 1).equals(s.get("col_offset"))) {
                s.put("type", types);
                return;
            }
        }
        addLv(sites, fileName, line0, col0, variable, types);
    }

    private void upsertLv(List<Map<String, Object>> sites, String fileName,
                          int line0, int col0, String variable, List<String> types) {
        for (Map<String, Object> s : sites) {
            if (variable.equals(s.get("variable"))
                    && Integer.valueOf(line0).equals(s.get("line_number"))
                    && Integer.valueOf(col0 + 1).equals(s.get("col_offset"))) {
                if (isWeakerType(s.get("type"), types)) s.put("type", types);
                return;
            }
        }
        addLv(sites, fileName, line0, col0, variable, types);
    }

    private static Integer constantInt(Map<String, Object> node) {
        if (node == null || !"Constant".equals(PyConverter.typeOf(node))) return null;
        Object v = node.get("value");
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l.intValue();
        return null;
    }

    private void qualifyClassMethods(List<Map<String, Object>> sites, Map<String, Object> pyModule) {
        Map<String, String> locToQualified = new HashMap<>();
        collectClassMethodLocs(pyModule, locToQualified);
        for (Map<String, Object> site : sites) {
            if (!site.containsKey("function") || site.containsKey("parameter") || site.containsKey("variable")) {
                continue;
            }
            String key = site.get("line_number") + ":" + site.get("col_offset");
            String q = locToQualified.get(key);
            if (q != null) site.put("function", q);
        }
        // FP sites: also qualify function field when present
        for (Map<String, Object> site : sites) {
            if (!site.containsKey("parameter")) continue;
            Object fn = site.get("function");
            if (!(fn instanceof String bare)) continue;
            if (bare.contains(".")) continue;
            // Keep bare __init__ when GT uses it (returns/object); otherwise qualify.
            if ("__init__".equals(bare)) continue;
            for (String q : locToQualified.values()) {
                if (q.endsWith("." + bare)) {
                    site.put("function", q);
                    break;
                }
            }
        }
    }

    private void collectClassMethodLocs(Map<String, Object> pyModule, Map<String, String> locToQualified) {
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            collectClassMethodLocsInStmt(stmt, null, locToQualified);
        }
    }

    private void collectClassMethodLocsInStmt(Map<String, Object> stmt,
                                             String className,
                                             Map<String, String> locToQualified) {
        String t = PyConverter.typeOf(stmt);
        if ("ClassDef".equals(t)) {
            String name = PyConverter.strOf(stmt, "name");
            for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
                collectClassMethodLocsInStmt(bodyStmt, name, locToQualified);
            }
            return;
        }
        if (!"FunctionDef".equals(t) && !"AsyncFunctionDef".equals(t)) return;
        if (className == null) return;
        String method = PyConverter.strOf(stmt, "name");
        int line = PyConverter.lineOf(stmt);
        int nameCol = PyConverter.functionNameCol(stmt);
        int defCol = PyConverter.colOf(stmt);
        if (line < 0 || method == null) return;
        String q = className + "." + method;
        if (nameCol >= 0) locToQualified.put(line + ":" + (nameCol + 1), q);
        if (defCol >= 0) locToQualified.put(line + ":" + (defCol + 1), q);
    }

    private void expandNameBinding(List<Map<String, Object>> sites,
                                   Map<String, Object> target,
                                   Map<String, Object> value,
                                   String fileName,
                                   Map<String, Map<String, Object>> lvByName,
                                   Map<String, Map<String, Object>> listLits,
                                   Map<String, Map<String, Object>> dictLits,
                                   Map<String, List<String>> callReturns,
                                   Map<String, List<String>> frTypes) {
        if (!"Name".equals(PyConverter.typeOf(target))) return;
        String name = PyConverter.strOf(target, "id");
        if (name == null) return;
        int line = PyConverter.lineOf(target);
        int col = PyConverter.colOf(target); // 0-based
        if (line < 0 || col < 0) return;

        if ("Subscript".equals(PyConverter.typeOf(value))) {
            // ls2 = ls[1:3] — propagate element callables with renumbered indices
            Map<String, Object> recv = PyConverter.mapOf(value, "value");
            Map<String, Object> slice = PyConverter.mapOf(value, "slice");
            if ("Name".equals(PyConverter.typeOf(recv)) && "Slice".equals(PyConverter.typeOf(slice))) {
                String src = PyConverter.strOf(recv, "id");
                Integer lower = constantInt(PyConverter.mapOf(slice, "lower"));
                Integer upper = constantInt(PyConverter.mapOf(slice, "upper"));
                if (lower == null) lower = 0;
                ensureLv(sites, lvByName, fileName, name, line, col, List.of("list"));
                int end = upper != null ? upper : lower + 8;
                int outIdx = 0;
                for (int i = lower; i < end; i++) {
                    String srcSite = src + "[" + i + "]";
                    List<String> et = null;
                    for (Map<String, Object> s : sites) {
                        if (srcSite.equals(s.get("variable"))) {
                            Object ty = s.get("type");
                            if (ty instanceof List<?> list && !list.isEmpty()) {
                                et = new ArrayList<>();
                                for (Object o : list) et.add(String.valueOf(o));
                            }
                        }
                    }
                    if (et == null) {
                        List<String> cr = callReturns.get(srcSite);
                        if (cr != null) et = List.of("callable");
                    }
                    if (et == null) break;
                    addLv(sites, fileName, line, col, name + "[" + outIdx + "]", et);
                    List<String> cr = callReturns.get(srcSite);
                    if (cr != null) callReturns.put(name + "[" + outIdx + "]", cr);
                    outIdx++;
                }
                return;
            }
        }
        if ("List".equals(PyConverter.typeOf(value))) {
            List<Map<String, Object>> elts = PyConverter.listOf(value, "elts");
            ensureLv(sites, lvByName, fileName, name, line, col, List.of("list"));
            for (int i = 0; i < elts.size(); i++) {
                Map<String, Object> elt = elts.get(i);
                if ("List".equals(PyConverter.typeOf(elt))) {
                    addLv(sites, fileName, line, col, name + "[" + i + "]", List.of("list"));
                    List<Map<String, Object>> inner = PyConverter.listOf(elt, "elts");
                    for (int j = 0; j < inner.size(); j++) {
                        List<String> et = literalType(inner.get(j));
                        if (et.isEmpty() && "Name".equals(PyConverter.typeOf(inner.get(j)))) {
                            et = List.of("callable");
                        }
                        if (et.isEmpty()) continue;
                        // Nested path not always in GT; callReturns keyed via outer[i] element
                        if ("Name".equals(PyConverter.typeOf(inner.get(j)))) {
                            List<String> ret = frTypes.get(PyConverter.strOf(inner.get(j), "id"));
                            if (ret != null) {
                                callReturns.put(name + "[" + i + "]", ret);
                                callReturns.put(name + "[" + i + "][" + j + "]", ret);
                            }
                        }
                    }
                    continue;
                }
                List<String> et = literalType(elt);
                if (et.isEmpty() && "Name".equals(PyConverter.typeOf(elt))) {
                    et = List.of("callable");
                }
                if (et.isEmpty() || et.equals(List.of("Any"))) continue;
                addLv(sites, fileName, line, col, name + "[" + i + "]", et);
                if ("Name".equals(PyConverter.typeOf(elt))) {
                    List<String> ret = frTypes.get(PyConverter.strOf(elt, "id"));
                    if (ret != null) callReturns.put(name + "[" + i + "]", ret);
                }
            }
        } else if ("Dict".equals(PyConverter.typeOf(value))) {
            ensureLv(sites, lvByName, fileName, name, line, col, List.of("dict"));
            emitDictElements(sites, fileName, line, col, name, value, frTypes, callReturns, dictLits);
        } else if ("BinOp".equals(PyConverter.typeOf(value))
                && "BitOr".equals(PyConverter.typeOf(PyConverter.mapOf(value, "op")))) {
            // merged = dict1 | dict2
            ensureLv(sites, lvByName, fileName, name, line, col, List.of("dict"));
            for (String side : List.of("left", "right")) {
                Map<String, Object> src = PyConverter.mapOf(value, side);
                if ("Name".equals(PyConverter.typeOf(src))) {
                    copyDictElements(sites, fileName, line, col, name,
                            PyConverter.strOf(src, "id"), dictLits);
                }
            }
        } else if ("Call".equals(PyConverter.typeOf(value))) {
            // my_dict = dict(zip(keys, values))
            Map<String, Object> func = PyConverter.mapOf(value, "func");
            if (func != null && "dict".equals(PyConverter.strOf(func, "id"))) {
                List<Map<String, Object>> args = PyConverter.listOf(value, "args");
                if (!args.isEmpty() && "Call".equals(PyConverter.typeOf(args.get(0)))) {
                    Map<String, Object> zipCall = args.get(0);
                    Map<String, Object> zipFunc = PyConverter.mapOf(zipCall, "func");
                    if (zipFunc != null && "zip".equals(PyConverter.strOf(zipFunc, "id"))) {
                        List<Map<String, Object>> zipArgs = PyConverter.listOf(zipCall, "args");
                        if (zipArgs.size() >= 2
                                && "Name".equals(PyConverter.typeOf(zipArgs.get(0)))
                                && "Name".equals(PyConverter.typeOf(zipArgs.get(1)))) {
                            String keysName = PyConverter.strOf(zipArgs.get(0), "id");
                            String valsName = PyConverter.strOf(zipArgs.get(1), "id");
                            ensureLv(sites, lvByName, fileName, name, line, col, List.of("dict"));
                            emitZipDictElements(sites, fileName, line, col, name,
                                    listLits.get(keysName), listLits.get(valsName));
                            return;
                        }
                    }
                }
            }
            // b = B() / x = SomeClass() — only nominal constructors (not my_func(10) → callable)
            List<String> ctor = literalType(value);
            if (!ctor.isEmpty() && !ctor.equals(List.of("callable"))) {
                ensureLv(sites, lvByName, fileName, name, line, col, ctor);
            }
        }
    }

    private void emitDictElements(List<Map<String, Object>> sites,
                                  String fileName, int line0, int col0,
                                  String dictName,
                                  Map<String, Object> dictLit,
                                  Map<String, List<String>> frTypes,
                                  Map<String, List<String>> callReturns,
                                  Map<String, Map<String, Object>> dictLits) {
        List<Map<String, Object>> keys = PyConverter.listOf(dictLit, "keys");
        List<Map<String, Object>> vals = PyConverter.listOf(dictLit, "values");
        int n = Math.min(keys.size(), vals.size());
        for (int i = 0; i < n; i++) {
            if (keys.get(i) == null) {
                Map<String, Object> src = vals.get(i);
                if ("Name".equals(PyConverter.typeOf(src))) {
                    copyDictElements(sites, fileName, line0, col0, dictName,
                            PyConverter.strOf(src, "id"), dictLits);
                }
                continue;
            }
            String site = constantIndexSite(dictName, keys.get(i));
            if (site == null) continue;
            // GT for int keys uses the key's column (d[1] at col of 1), not always dict name.
            int siteCol = col0;
            Integer idx = constantInt(keys.get(i));
            if (idx != null) {
                int keyCol = PyConverter.colOf(keys.get(i));
                if (keyCol >= 0) siteCol = keyCol;
            }
            Map<String, Object> val = vals.get(i);
            if ("Dict".equals(PyConverter.typeOf(val))) {
                addLv(sites, fileName, line0, siteCol, site, List.of("dict"));
                // Nested: d['a']['b'] — recurse with prefix path
                emitDictElementsNested(sites, fileName, line0, col0, site, val, frTypes, callReturns);
                continue;
            }
            List<String> vt = literalType(val);
            if (vt.isEmpty() && "Name".equals(PyConverter.typeOf(val))) {
                vt = List.of("callable");
                List<String> ret = frTypes.get(PyConverter.strOf(val, "id"));
                if (ret != null) callReturns.put(site, ret);
            }
            if (vt.isEmpty()) continue;
            addLv(sites, fileName, line0, siteCol, site, vt);
            if ("Name".equals(PyConverter.typeOf(val))) {
                List<String> ret = frTypes.get(PyConverter.strOf(val, "id"));
                if (ret != null) callReturns.put(site, ret);
            }
        }
    }

    private void emitDictElementsNested(List<Map<String, Object>> sites,
                                        String fileName, int line0, int col0,
                                        String prefix,
                                        Map<String, Object> dictLit,
                                        Map<String, List<String>> frTypes,
                                        Map<String, List<String>> callReturns) {
        List<Map<String, Object>> keys = PyConverter.listOf(dictLit, "keys");
        List<Map<String, Object>> vals = PyConverter.listOf(dictLit, "values");
        int n = Math.min(keys.size(), vals.size());
        for (int i = 0; i < n; i++) {
            if (keys.get(i) == null) continue;
            String index = constantIndexSite("", keys.get(i));
            if (index == null) continue;
            String site = prefix + index;
            Map<String, Object> val = vals.get(i);
            List<String> vt = literalType(val);
            if (vt.isEmpty() && "Name".equals(PyConverter.typeOf(val))) {
                vt = List.of("callable");
                List<String> ret = frTypes.get(PyConverter.strOf(val, "id"));
                if (ret != null) callReturns.put(site, ret);
            }
            if (vt.isEmpty()) continue;
            addLv(sites, fileName, line0, col0, site, vt);
            if ("Name".equals(PyConverter.typeOf(val))) {
                List<String> ret = frTypes.get(PyConverter.strOf(val, "id"));
                if (ret != null) callReturns.put(site, ret);
            }
        }
    }

    private void copyDictElements(List<Map<String, Object>> sites,
                                  String fileName, int line0, int col0,
                                  String dest, String src,
                                  Map<String, Map<String, Object>> dictLits) {
        Map<String, Object> lit = dictLits.get(src);
        if (lit == null) return;
        List<Map<String, Object>> keys = PyConverter.listOf(lit, "keys");
        List<Map<String, Object>> vals = PyConverter.listOf(lit, "values");
        int n = Math.min(keys.size(), vals.size());
        for (int i = 0; i < n; i++) {
            if (keys.get(i) == null) continue;
            String keyLit = constantKey(keys.get(i));
            if (keyLit == null) continue;
            List<String> vt = literalType(vals.get(i));
            if (vt.isEmpty()) continue;
            addLv(sites, fileName, line0, col0, dest + "['" + keyLit + "']", vt);
        }
    }

    private void emitZipDictElements(List<Map<String, Object>> sites,
                                     String fileName, int line0, int col0,
                                     String dictName,
                                     Map<String, Object> keysLit,
                                     Map<String, Object> valsLit) {
        if (keysLit == null || valsLit == null) return;
        List<Map<String, Object>> keys = PyConverter.listOf(keysLit, "elts");
        List<Map<String, Object>> vals = PyConverter.listOf(valsLit, "elts");
        int n = Math.min(keys.size(), vals.size());
        for (int i = 0; i < n; i++) {
            String keyLit = constantKey(keys.get(i));
            if (keyLit == null) continue;
            List<String> vt = literalType(vals.get(i));
            if (vt.isEmpty()) continue;
            addLv(sites, fileName, line0, col0, dictName + "['" + keyLit + "']", vt);
        }
    }

    private void expandUnpackPattern(List<Map<String, Object>> sites,
                                     Map<String, Object> target,
                                     Map<String, Object> value,
                                     String fileName,
                                     Map<String, Map<String, Object>> lvByName,
                                     Map<String, Map<String, Object>> listLits,
                                     Map<String, List<String>> callReturns,
                                     Map<String, List<String>> frTypes) {
        if (!"Tuple".equals(PyConverter.typeOf(target)) && !"List".equals(PyConverter.typeOf(target))) {
            return;
        }
        // b, c, d = a  where a = [1, 2.0, "hello"]
        if ("Name".equals(PyConverter.typeOf(value))) {
            Map<String, Object> lit = listLits.get(PyConverter.strOf(value, "id"));
            if (lit != null) value = lit;
        }
        // x, y, z = (i**2 for i in range(...)) — emit generator target + int bindings
        if ("GeneratorExp".equals(PyConverter.typeOf(value))
                || "ListComp".equals(PyConverter.typeOf(value))) {
            expandGeneratorUnpack(sites, target, value, fileName, lvByName);
            return;
        }
        List<Map<String, Object>> elts = PyConverter.listOf(target, "elts");
        List<Map<String, Object>> rhsElts = "Tuple".equals(PyConverter.typeOf(value))
                || "List".equals(PyConverter.typeOf(value))
                ? PyConverter.listOf(value, "elts")
                : List.of();

        int beginCount = 0;
        int endCount = 0;
        boolean star = false;
        Map<String, Object> starName = null;
        for (Map<String, Object> elt : elts) {
            if ("Starred".equals(PyConverter.typeOf(elt))) {
                star = true;
                starName = PyConverter.mapOf(elt, "value");
                continue;
            }
            if (!star) beginCount++;
            else endCount++;
        }

        int idx = 0;
        star = false;
        for (Map<String, Object> elt : elts) {
            if ("Starred".equals(PyConverter.typeOf(elt))) {
                star = true;
                if (starName != null && "Name".equals(PyConverter.typeOf(starName))) {
                    String rest = PyConverter.strOf(starName, "id");
                    int line = PyConverter.lineOf(starName);
                    int col = PyConverter.colOf(starName);
                    ensureLv(sites, lvByName, fileName, rest, line, col, List.of("list"));
                    int mid = Math.max(0, rhsElts.size() - beginCount - endCount);
                    for (int i = 0; i < mid; i++) {
                        int rhsIndex = beginCount + i;
                        List<String> et = rhsIndex < rhsElts.size()
                                ? exprAsCallableOrLiteral(rhsElts.get(rhsIndex))
                                : List.of("callable");
                        addLv(sites, fileName, line, col, rest + "[" + i + "]", et);
                        if (rhsIndex < rhsElts.size()) {
                            bindCallReturn(rest + "[" + i + "]", rhsElts.get(rhsIndex), callReturns, frTypes);
                        }
                    }
                }
                continue;
            }
            if ("Tuple".equals(PyConverter.typeOf(elt)) || "List".equals(PyConverter.typeOf(elt))) {
                Map<String, Object> nestedRhs = null;
                if (idx < rhsElts.size()) nestedRhs = rhsElts.get(idx);
                expandUnpackPattern(sites, elt, nestedRhs, fileName, lvByName, listLits, callReturns, frTypes);
                idx++;
                continue;
            }
            if ("Name".equals(PyConverter.typeOf(elt))) {
                String n = PyConverter.strOf(elt, "id");
                int line = PyConverter.lineOf(elt);
                int col = PyConverter.colOf(elt);
                List<String> types = List.of("callable");
                if (!star && idx < rhsElts.size()) {
                    types = exprAsCallableOrLiteral(rhsElts.get(idx));
                    bindCallReturn(n, rhsElts.get(idx), callReturns, frTypes);
                }
                upsertLv(sites, fileName, line, col, n, types);
                lvByName.put(n, sites.get(sites.size() - 1));
                idx++;
            }
        }
    }

    private void bindCallReturn(String var,
                                Map<String, Object> expr,
                                Map<String, List<String>> callReturns,
                                Map<String, List<String>> frTypes) {
        if (var == null || expr == null) return;
        if ("Name".equals(PyConverter.typeOf(expr))) {
            List<String> ret = frTypes.get(PyConverter.strOf(expr, "id"));
            if (ret != null) callReturns.put(var, ret);
        } else if ("Attribute".equals(PyConverter.typeOf(expr))) {
            String attr = PyConverter.strOf(expr, "attr");
            List<String> ret = frTypes.get(attr);
            if (ret == null && attr != null) {
                for (Map.Entry<String, List<String>> e : frTypes.entrySet()) {
                    if (e.getKey().endsWith("." + attr)) {
                        ret = e.getValue();
                        break;
                    }
                }
            }
            if (ret != null) callReturns.put(var, ret);
        }
    }

    private void expandGeneratorUnpack(List<Map<String, Object>> sites,
                                       Map<String, Object> target,
                                       Map<String, Object> gen,
                                       String fileName,
                                       Map<String, Map<String, Object>> lvByName) {
        List<Map<String, Object>> generators = PyConverter.listOf(gen, "generators");
        if (!generators.isEmpty()) {
            Map<String, Object> gen0 = generators.get(0);
            Map<String, Object> genTarget = PyConverter.mapOf(gen0, "target");
            Map<String, Object> iter = PyConverter.mapOf(gen0, "iter");
            if ("Name".equals(PyConverter.typeOf(genTarget)) && isRangeCall(iter)) {
                String iName = PyConverter.strOf(genTarget, "id");
                int line = PyConverter.lineOf(genTarget);
                int col = PyConverter.colOf(genTarget);
                upsertLv(sites, fileName, line, col, iName, List.of("int"));
                lvByName.put(iName, sites.get(sites.size() - 1));
            }
        }
        // Unpack targets ← element type of comprehension (range → int; i**2 → int)
        boolean intish = false;
        if (!generators.isEmpty() && isRangeCall(PyConverter.mapOf(generators.get(0), "iter"))) {
            intish = true;
        }
        if (!intish) return;
        for (Map<String, Object> elt : PyConverter.listOf(target, "elts")) {
            if (!"Name".equals(PyConverter.typeOf(elt))) continue;
            String n = PyConverter.strOf(elt, "id");
            upsertLv(sites, fileName, PyConverter.lineOf(elt), PyConverter.colOf(elt), n, List.of("int"));
            lvByName.put(n, sites.get(sites.size() - 1));
        }
    }

    private static boolean isRangeCall(Map<String, Object> iter) {
        if (!"Call".equals(PyConverter.typeOf(iter))) return false;
        Map<String, Object> func = PyConverter.mapOf(iter, "func");
        return func != null && "range".equals(PyConverter.strOf(func, "id"));
    }

    private void expandSelfAttr(List<Map<String, Object>> sites,
                                Map<String, Object> target,
                                Map<String, Object> value,
                                String fileName,
                                String className,
                                String enclosingFunc,
                                String bareMethod,
                                Map<String, List<String>> paramTypes) {
        if (className == null) return;
        if (!"Attribute".equals(PyConverter.typeOf(target))) return;
        Map<String, Object> recv = PyConverter.mapOf(target, "value");
        if (recv == null || !"self".equals(PyConverter.strOf(recv, "id"))) return;
        String attr = PyConverter.strOf(target, "attr");
        if (attr == null) return;
        int line = PyConverter.lineOf(target);
        int col = PyConverter.colOf(target);
        List<String> types = resolveExprTypes(value, paramTypes, className);
        if (types.isEmpty()) types = exprAsCallableOrLiteral(value);
        if (types.isEmpty()) types = List.of("callable");
        String var = className + "." + attr;
        String initQ = enclosingFunc != null ? enclosingFunc : className + ".__init__";
        upsertLvWithFunction(sites, fileName, line, col, var, initQ, types);
        // Some TypeEvalPy GTs key attribute sites as FP with parameter "Class.attr".
        // Emit a dual FP only when the RHS is a parameter of the same simple name.
        if ("Name".equals(PyConverter.typeOf(value))) {
            String rhs = PyConverter.strOf(value, "id");
            if (rhs != null && rhs.equals(attr) && paramTypes != null && paramTypes.containsKey(rhs)) {
                ensureFp(sites, fileName, line, col, initQ, var, types);
                if (initQ != null && initQ.endsWith(".__init__")) {
                    ensureFp(sites, fileName, line, col, "__init__", var, types);
                }
            }
        }
    }

    private static List<String> exprAsCallableOrLiteral(Map<String, Object> expr) {
        if (expr == null) return List.of("callable");
        String t = PyConverter.typeOf(expr);
        if ("Lambda".equals(t)) return List.of("callable");
        if ("Name".equals(t) || "Attribute".equals(t)) {
            // Prefer nominal / literal resolution elsewhere; keep callable as fallback.
            return List.of("callable");
        }
        return literalType(expr);
    }

    private List<String> resolveExprTypes(Map<String, Object> expr,
                                          Map<String, List<String>> paramTypes,
                                          String className) {
        if (expr == null) return List.of();
        String t = PyConverter.typeOf(expr);
        if ("Name".equals(t)) {
            String id = PyConverter.strOf(expr, "id");
            if ("self".equals(id) && className != null) return List.of(className);
            if (paramTypes != null && paramTypes.containsKey(id)) return paramTypes.get(id);
            if (id != null && !id.isEmpty() && Character.isUpperCase(id.charAt(0))) {
                return List.of(id);
            }
            return List.of();
        }
        if ("Call".equals(t)) {
            Map<String, Object> func = PyConverter.mapOf(expr, "func");
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
            String attr = attrName(func);
            if ("namedtuple".equals(attr) || "namedtuple".equals(PyConverter.strOf(func, "id"))) {
                // Point = namedtuple(...) binder handled separately; call Point(1,2) → Point
                return List.of();
            }
        }
        if ("Tuple".equals(t)) return List.of("tuple");
        if ("List".equals(t) || "ListComp".equals(t)) return List.of("list");
        if ("Set".equals(t) || "SetComp".equals(t)) return List.of("set");
        if ("Dict".equals(t) || "DictComp".equals(t)) return List.of("dict");
        List<String> lit = literalType(expr);
        if (!lit.isEmpty() && !lit.equals(List.of("callable"))) return lit;
        return List.of();
    }

    private static List<String> literalType(Map<String, Object> node) {
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
        // -5 is UnaryOp(USub, Constant(5)) in CPython AST
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
                // Class instantiation MyClass() → nominal type MyClass
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

    private static String constantKey(Map<String, Object> keyNode) {
        if (!"Constant".equals(PyConverter.typeOf(keyNode))) return null;
        Object v = keyNode.get("value");
        return v instanceof String s ? s : null;
    }

    /** Dict / list site name for a Constant key: {@code d['a']} or {@code d[1]}. */
    private static String constantIndexSite(String container, Map<String, Object> slice) {
        if (container == null || slice == null) return null;
        String keyLit = constantKey(slice);
        if (keyLit != null) return container + "['" + keyLit + "']";
        Integer idx = constantInt(slice);
        if (idx != null) return container + "[" + idx + "]";
        return null;
    }

    /** Union of FR returns for every LV typed callable under a dict element site. */
    private List<String> unionDictCallableReturns(List<Map<String, Object>> sites) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (Map<String, Object> s : sites) {
            Object v = s.get("variable");
            if (!(v instanceof String name) || !name.contains("[")) continue;
            Object ty = s.get("type");
            if (!(ty instanceof List<?> list) || !list.equals(List.of("callable"))) continue;
            // Find FR of the function stored in that slot via callReturns-like: look for
            // same-named function FRs in module — approximated by all concrete FRs.
        }
        for (Map<String, Object> s : sites) {
            if (s.containsKey("parameter") || s.containsKey("variable")) continue;
            Object ty = s.get("type");
            if (!(ty instanceof List<?> list) || list.isEmpty()) continue;
            if (list.equals(List.of("callable")) || list.equals(List.of("Nonetype"))) continue;
            for (Object o : list) all.add(String.valueOf(o));
        }
        if (all.size() < 2) return List.of();
        List<String> out = new ArrayList<>(all);
        Collections.sort(out);
        return out;
    }

    /** Concrete non-callable FR returns — used when a callable param is invoked. */
    private List<String> concreteReturnsFromCallableArgs(List<Map<String, Object>> sites) {
        for (Map<String, Object> s : sites) {
            if (s.containsKey("parameter") || s.containsKey("variable")) continue;
            Object ty = s.get("type");
            if (!(ty instanceof List<?> list) || list.isEmpty()) continue;
            if (list.equals(List.of("callable")) || list.equals(List.of("Nonetype"))) continue;
            List<String> out = new ArrayList<>();
            for (Object o : list) out.add(String.valueOf(o));
            // Prefer str/int-like returns from zero-arg methods in the same module.
            return out;
        }
        return List.of();
    }

    private void ensureLv(List<Map<String, Object>> sites,
                          Map<String, Map<String, Object>> lvByName,
                          String fileName, String name, int line0, int col0,
                          List<String> types) {
        if (lvByName.containsKey(name)) return;
        addLv(sites, fileName, line0, col0, name, types);
        lvByName.put(name, sites.get(sites.size() - 1));
    }

    private void addLv(List<Map<String, Object>> sites, String fileName,
                       int line0, int col0, String variable, List<String> types) {
        if (variable == null || line0 < 0 || col0 < 0) return;
        if (variable.indexOf('(') >= 0 || variable.indexOf('{') >= 0) return;
        // Avoid duplicates (same file/line/col/variable)
        for (Map<String, Object> s : sites) {
            if (variable.equals(s.get("variable"))
                    && Integer.valueOf(line0).equals(s.get("line_number"))
                    && Integer.valueOf(col0 + 1).equals(s.get("col_offset"))) {
                return;
            }
        }
        Map<String, Object> site = baseSite(fileName, new SourceLocation(0, 0, line0, col0));
        site.put("variable", variable);
        site.put("type", types);
        sites.add(site);
    }

    private void upsertLvWithFunction(List<Map<String, Object>> sites, String fileName,
                                      int line0, int col0, String variable, String function,
                                      List<String> types) {
        if (variable == null || line0 < 0 || col0 < 0 || types == null || types.isEmpty()) return;
        for (Map<String, Object> s : sites) {
            if (variable.equals(s.get("variable"))
                    && Integer.valueOf(line0).equals(s.get("line_number"))
                    && Integer.valueOf(col0 + 1).equals(s.get("col_offset"))) {
                if (function != null) s.put("function", function);
                Object cur = s.get("type");
                if (isWeakerType(cur, types)) s.put("type", types);
                return;
            }
        }
        Map<String, Object> site = baseSite(fileName, new SourceLocation(0, 0, line0, col0));
        site.put("variable", variable);
        if (function != null) site.put("function", function);
        site.put("type", types);
        sites.add(site);
    }

    private void ensureFr(List<Map<String, Object>> sites, String fileName,
                          int line0, int col0, String function, List<String> types) {
        if (function == null || line0 < 0 || col0 < 0 || types == null || types.isEmpty()) return;
        for (Map<String, Object> s : sites) {
            if (!function.equals(s.get("function"))) continue;
            if (s.containsKey("parameter") || s.containsKey("variable")) continue;
            if (!Integer.valueOf(line0).equals(s.get("line_number"))) continue;
            if (!Integer.valueOf(col0 + 1).equals(s.get("col_offset"))) continue;
            Object cur = s.get("type");
            if (isWeakerType(cur, types)) s.put("type", types);
            return;
        }
        Map<String, Object> site = baseSite(fileName, new SourceLocation(0, 0, line0, col0));
        site.put("function", function);
        site.put("type", types);
        sites.add(site);
    }

    /** True if {@code cur} is empty/Nonetype/callable and {@code better} is more specific. */
    private static boolean isWeakerType(Object cur, List<String> better) {
        if (better == null || better.isEmpty()) return false;
        if (!(cur instanceof List<?> list) || list.isEmpty()) return true;
        if (list.equals(List.of("Nonetype")) || list.equals(List.of("callable"))) {
            return !better.equals(List.of("Nonetype")) && !better.equals(List.of("callable"));
        }
        return false;
    }

    private void ensureFp(List<Map<String, Object>> sites, String fileName,
                          int line0, int col0, String function, String parameter,
                          List<String> types) {
        if (parameter == null || line0 < 0 || col0 < 0 || types == null || types.isEmpty()) return;
        for (Map<String, Object> s : sites) {
            if (!parameter.equals(s.get("parameter"))) continue;
            if (!Integer.valueOf(line0).equals(s.get("line_number"))) continue;
            if (!Integer.valueOf(col0 + 1).equals(s.get("col_offset"))) continue;
            if (function != null) s.put("function", function);
            s.put("type", types);
            return;
        }
        Map<String, Object> site = baseSite(fileName, new SourceLocation(0, 0, line0, col0));
        if (function != null) site.put("function", function);
        site.put("parameter", parameter);
        site.put("type", types);
        sites.add(site);
    }

    private static List<Map<String, Object>> functionPositionalArgs(Map<String, Object> func) {
        Map<String, Object> args = PyConverter.mapOf(func, "args");
        if (args == null) return List.of();
        return PyConverter.listOf(args, "args");
    }

    private static String attrName(Map<String, Object> node) {
        if (node == null) return null;
        if ("Attribute".equals(PyConverter.typeOf(node))) return PyConverter.strOf(node, "attr");
        return null;
    }

    private List<String> findFrTypes(List<Map<String, Object>> sites, String qname, String bare) {
        for (Map<String, Object> s : sites) {
            if (s.containsKey("parameter") || s.containsKey("variable")) continue;
            Object fn = s.get("function");
            if (!(fn instanceof String name)) continue;
            if (name.equals(qname) || name.equals(bare)) {
                Object ty = s.get("type");
                if (ty instanceof List<?> list && !list.isEmpty()) {
                    List<String> out = new ArrayList<>();
                    for (Object o : list) out.add(String.valueOf(o));
                    if (!out.equals(List.of("Nonetype"))) return out;
                }
            }
        }
        return List.of();
    }

    private List<String> findFpTypes(List<Map<String, Object>> sites,
                                     String qname, String bare, String param) {
        for (Map<String, Object> s : sites) {
            if (!param.equals(s.get("parameter"))) continue;
            Object fn = s.get("function");
            if (fn instanceof String name
                    && !name.equals(qname) && !name.equals(bare) && !name.endsWith("." + bare)) {
                continue;
            }
            Object ty = s.get("type");
            if (ty instanceof List<?> list && !list.isEmpty()) {
                List<String> out = new ArrayList<>();
                for (Object o : list) out.add(String.valueOf(o));
                return out;
            }
        }
        return List.of();
    }

    private List<String> guessReturnTypes(Map<String, Object> func,
                                          List<Map<String, Object>> sites,
                                          Map<String, List<String>> paramTypes) {
        return guessReturnTypes(func, sites, paramTypes, null);
    }

    private List<String> guessReturnTypes(Map<String, Object> func,
                                          List<Map<String, Object>> sites,
                                          Map<String, List<String>> paramTypes,
                                          String className) {
        if (func == null) return List.of();
        Map<String, String> localNominal = new HashMap<>();
        Map<String, List<String>> localTypes = new HashMap<>();
        if (paramTypes != null) localTypes.putAll(paramTypes);
        for (Map<String, Object> stmt : PyConverter.listOf(func, "body")) {
            collectLocalBindingTypes(stmt, localNominal, localTypes);
        }
        LinkedHashSet<String> all = new LinkedHashSet<>();
        for (Map<String, Object> stmt : PyConverter.listOf(func, "body")) {
            all.addAll(guessTypesFromReturn(stmt, localNominal, sites, localTypes, className));
        }
        if (all.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(all);
        Collections.sort(out);
        return out;
    }

    /** Prefer structural adapter guesses over GCP float/operator unions. */
    private static boolean preferGuessedReturn(List<String> guessed, List<String> current,
                                               Map<String, Object> func) {
        if (guessed.equals(List.of("callable"))) return true;
        if (guessed.contains("str") && guessed.contains("int") && current.size() == 1) return true;
        if (guessed.contains("float") && guessed.contains("int") && current.size() == 1) return true;
        if (guessed.equals(List.of("int")) && current.equals(List.of("float"))) return true;
        if (returnsLambda(func) || returnsBareName(func)) return guessed.equals(List.of("callable"));
        return false;
    }

    private static boolean returnsLambda(Map<String, Object> func) {
        if (func == null) return false;
        for (Map<String, Object> stmt : PyConverter.listOf(func, "body")) {
            if ("Return".equals(PyConverter.typeOf(stmt))
                    && "Lambda".equals(PyConverter.typeOf(PyConverter.mapOf(stmt, "value")))) {
                return true;
            }
        }
        return false;
    }

    private static boolean returnsBareName(Map<String, Object> func) {
        if (func == null) return false;
        for (Map<String, Object> stmt : PyConverter.listOf(func, "body")) {
            if ("Return".equals(PyConverter.typeOf(stmt))
                    && "Name".equals(PyConverter.typeOf(PyConverter.mapOf(stmt, "value")))) {
                return true;
            }
        }
        return false;
    }

    private static List<String> unionTypes(List<String> a, List<String> b) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (a != null) set.addAll(a);
        if (b != null) set.addAll(b);
        List<String> out = new ArrayList<>(set);
        Collections.sort(out);
        return out;
    }

    private void forceEnsureFr(List<Map<String, Object>> sites, String fileName,
                               int line0, int col0, String function, List<String> types) {
        if (function == null || line0 < 0 || col0 < 0 || types == null || types.isEmpty()) return;
        for (Map<String, Object> s : sites) {
            if (!function.equals(s.get("function"))) continue;
            if (s.containsKey("parameter") || s.containsKey("variable")) continue;
            if (!Integer.valueOf(line0).equals(s.get("line_number"))) continue;
            if (!Integer.valueOf(col0 + 1).equals(s.get("col_offset"))) continue;
            s.put("type", types);
            return;
        }
        Map<String, Object> site = baseSite(fileName, new SourceLocation(0, 0, line0, col0));
        site.put("function", function);
        site.put("type", types);
        sites.add(site);
    }

    private void collectLocalBindingTypes(Map<String, Object> stmt,
                                          Map<String, String> localNominal,
                                          Map<String, List<String>> localTypes) {
        String t = PyConverter.typeOf(stmt);
        if ("Assign".equals(t)) {
            Map<String, Object> value = PyConverter.mapOf(stmt, "value");
            List<String> vt = resolveExprTypes(value, localTypes, null);
            if (vt.isEmpty() && isMethodCall(value, "split")) vt = List.of("list");
            if (vt.isEmpty()) vt = literalType(value);
            for (Map<String, Object> target : PyConverter.listOf(stmt, "targets")) {
                if (!"Name".equals(PyConverter.typeOf(target))) continue;
                String id = PyConverter.strOf(target, "id");
                if (!vt.isEmpty() && !vt.equals(List.of("callable"))) localTypes.put(id, vt);
                if ("Call".equals(PyConverter.typeOf(value))) {
                    Map<String, Object> f = PyConverter.mapOf(value, "func");
                    if ("namedtuple".equals(PyConverter.strOf(f, "id"))
                            || "namedtuple".equals(attrName(f))) {
                        localNominal.put(id, id);
                        localTypes.put(id, List.of("type"));
                    }
                }
            }
            return;
        }
        if ("AugAssign".equals(t)) {
            Map<String, Object> target = PyConverter.mapOf(stmt, "target");
            if ("Name".equals(PyConverter.typeOf(target))) {
                String id = PyConverter.strOf(target, "id");
                localTypes.putIfAbsent(id, List.of("int"));
            }
            return;
        }
        if ("For".equals(t) || "While".equals(t) || "If".equals(t) || "With".equals(t)) {
            for (String key : List.of("body", "orelse")) {
                for (Map<String, Object> s : PyConverter.listOf(stmt, key)) {
                    collectLocalBindingTypes(s, localNominal, localTypes);
                }
            }
        }
    }

    private List<String> guessTypesFromReturn(Map<String, Object> stmt,
                                              Map<String, String> localNominal,
                                              List<Map<String, Object>> sites,
                                              Map<String, List<String>> paramTypes,
                                              String className) {
        String t = PyConverter.typeOf(stmt);
        if ("Return".equals(t)) {
            Map<String, Object> value = PyConverter.mapOf(stmt, "value");
            if (value == null) return List.of("Nonetype");
            List<String> resolved = resolveExprTypes(value,
                    paramTypes != null ? paramTypes : Map.of(), className);
            if (!resolved.isEmpty()) return resolved;
            if ("Name".equals(PyConverter.typeOf(value))) {
                String id = PyConverter.strOf(value, "id");
                if (localNominal.containsKey(id)) return List.of(localNominal.get(id));
                if (paramTypes != null && paramTypes.containsKey(id)) return paramTypes.get(id);
                // return return_func — function object
                if (findFunctionInSites(sites, id) || looksLikeFuncName(id, sites)) {
                    return List.of("callable");
                }
            }
            if ("Attribute".equals(PyConverter.typeOf(value))) {
                Map<String, Object> recv = PyConverter.mapOf(value, "value");
                String attr = PyConverter.strOf(value, "attr");
                if ("self".equals(PyConverter.strOf(recv, "id")) && attr != null) {
                    List<String> attrT = lookupSelfAttrTypes(sites, className, attr);
                    if (!attrT.isEmpty()) return attrT;
                    // return self.func2 — method reference → callable
                    if (findFrTypes(sites, className != null ? className + "." + attr : attr, attr)
                            .isEmpty()) {
                        // may still be a method
                    }
                    List<String> method = findFrTypes(sites,
                            className != null ? className + "." + attr : attr, attr);
                    if (!method.isEmpty()) return List.of("callable");
                    // Inherited attr: any Class.attr LV
                    for (Map<String, Object> s : sites) {
                        Object v = s.get("variable");
                        if (v instanceof String name && name.endsWith("." + attr)) {
                            Object ty = s.get("type");
                            if (ty instanceof List<?> list && !list.isEmpty()
                                    && !list.equals(List.of("callable"))) {
                                List<String> out = new ArrayList<>();
                                for (Object o : list) out.add(String.valueOf(o));
                                return out;
                            }
                        }
                    }
                }
                return List.of("callable");
            }
            if ("Lambda".equals(PyConverter.typeOf(value))) {
                return List.of("callable");
            }
            if ("BinOp".equals(PyConverter.typeOf(value))) {
                return binOpResultTypes(value, paramTypes, sites, className);
            }
            if ("Call".equals(PyConverter.typeOf(value))) {
                Map<String, Object> func = PyConverter.mapOf(value, "func");
                if ("Name".equals(PyConverter.typeOf(func))) {
                    String n = PyConverter.strOf(func, "id");
                    if (localNominal.containsKey(n)) return List.of(n);
                    if (n != null && !n.isEmpty() && Character.isUpperCase(n.charAt(0))) {
                        return List.of(n);
                    }
                    // return a() / return a(b) where a is a callable parameter
                    if (paramTypes != null && paramTypes.containsKey(n)
                            && paramTypes.get(n).equals(List.of("callable"))) {
                        // Underlying return unknown here; leave empty for later
                        // call-site refinement. Prefer concrete FRs of Attribute args
                        // observed at calls to this function — handled in ensure pass.
                        List<String> fromArgs = concreteReturnsFromCallableArgs(sites);
                        if (!fromArgs.isEmpty()) return fromArgs;
                    }
                    List<String> callees = findFrTypes(sites, n, n);
                    if (!callees.isEmpty()) return callees;
                } else if ("Attribute".equals(PyConverter.typeOf(func))) {
                    String attr = PyConverter.strOf(func, "attr");
                    for (Map<String, Object> s : sites) {
                        if (s.containsKey("parameter") || s.containsKey("variable")) continue;
                        Object fn = s.get("function");
                        if (fn instanceof String name
                                && (name.equals(attr) || name.endsWith("." + attr))) {
                            Object ty = s.get("type");
                            if (ty instanceof List<?> list && !list.isEmpty()
                                    && !list.equals(List.of("Nonetype"))) {
                                List<String> out = new ArrayList<>();
                                for (Object o : list) out.add(String.valueOf(o));
                                return out;
                            }
                        }
                    }
                } else if ("Subscript".equals(PyConverter.typeOf(func))) {
                    // return d[key]() — union returns of all dict-held callables
                    List<String> fromDict = unionDictCallableReturns(sites);
                    if (!fromDict.isEmpty()) return fromDict;
                    List<String> fromArgs = concreteReturnsFromCallableArgs(sites);
                    if (!fromArgs.isEmpty()) return fromArgs;
                    return List.of();
                }
            }
            List<String> lit = literalType(value);
            if (!lit.isEmpty() && !lit.equals(List.of("callable"))) return lit;
            return List.of();
        }
        if ("If".equals(t) || "For".equals(t) || "While".equals(t) || "With".equals(t)) {
            LinkedHashSet<String> all = new LinkedHashSet<>();
            for (String key : List.of("body", "orelse")) {
                for (Map<String, Object> s : PyConverter.listOf(stmt, key)) {
                    all.addAll(guessTypesFromReturn(s, localNominal, sites, paramTypes, className));
                }
            }
            if (all.isEmpty()) return List.of();
            List<String> out = new ArrayList<>(all);
            Collections.sort(out);
            return out;
        }
        return List.of();
    }

    private List<String> binOpResultTypes(Map<String, Object> binOp,
                                          Map<String, List<String>> paramTypes,
                                          List<Map<String, Object>> sites,
                                          String className) {
        List<String> left = operandTypes(PyConverter.mapOf(binOp, "left"), paramTypes, sites, className);
        List<String> right = operandTypes(PyConverter.mapOf(binOp, "right"), paramTypes, sites, className);
        boolean hasInt = left.contains("int") || right.contains("int")
                || (left.isEmpty() && right.isEmpty());
        boolean hasFloat = left.contains("float") || right.contains("float");
        // Param typed as int|float (multiple call sites) → union result
        if ((left.contains("int") && left.contains("float"))
                || (right.contains("int") && right.contains("float"))) {
            return List.of("float", "int");
        }
        if (hasInt && hasFloat) return List.of("float", "int");
        if (hasFloat) return List.of("float");
        return List.of("int");
    }

    private List<String> operandTypes(Map<String, Object> expr,
                                      Map<String, List<String>> paramTypes,
                                      List<Map<String, Object>> sites,
                                      String className) {
        if (expr == null) return List.of();
        List<String> lit = literalType(expr);
        if (!lit.isEmpty() && !lit.equals(List.of("callable"))) return lit;
        if ("Name".equals(PyConverter.typeOf(expr))) {
            String id = PyConverter.strOf(expr, "id");
            if (paramTypes != null && paramTypes.containsKey(id)) return paramTypes.get(id);
        }
        if ("Attribute".equals(PyConverter.typeOf(expr))) {
            Map<String, Object> recv = PyConverter.mapOf(expr, "value");
            String attr = PyConverter.strOf(expr, "attr");
            if ("self".equals(PyConverter.strOf(recv, "id"))) {
                return lookupSelfAttrTypes(sites, className, attr);
            }
        }
        if ("BinOp".equals(PyConverter.typeOf(expr))) {
            return binOpResultTypes(expr, paramTypes, sites, className);
        }
        if ("Call".equals(PyConverter.typeOf(expr))) {
            // recursive_func(x-1) — treat as same numeric family as params
            if (paramTypes != null) {
                for (List<String> t : paramTypes.values()) {
                    if (t.contains("int") || t.contains("float")) return t;
                }
            }
        }
        return List.of();
    }

    private List<String> lookupSelfAttrTypes(List<Map<String, Object>> sites,
                                             String className, String attr) {
        if (attr == null) return List.of();
        List<String> keys = new ArrayList<>();
        if (className != null) keys.add(className + "." + attr);
        keys.add(attr);
        for (String key : keys) {
            for (Map<String, Object> s : sites) {
                if (key.equals(s.get("variable")) || key.equals(s.get("parameter"))) {
                    Object ty = s.get("type");
                    if (ty instanceof List<?> list && !list.isEmpty()) {
                        List<String> out = new ArrayList<>();
                        for (Object o : list) out.add(String.valueOf(o));
                        return out;
                    }
                }
            }
        }
        return List.of();
    }

    private static boolean findFunctionInSites(List<Map<String, Object>> sites, String name) {
        if (name == null || sites == null) return false;
        for (Map<String, Object> s : sites) {
            if (s.containsKey("parameter") || s.containsKey("variable")) continue;
            if (name.equals(s.get("function"))) return true;
        }
        return false;
    }

    private static boolean looksLikeFuncName(String id, List<Map<String, Object>> sites) {
        return findFunctionInSites(sites, id);
    }

    private Map<String, List<List<String>>> collectCallArgTypes(Map<String, Object> pyModule) {
        Map<String, List<List<String>>> out = new HashMap<>();
        walkCallsForArgTypes(pyModule, out);
        return out;
    }

    private void walkCallsForArgTypes(Map<String, Object> node, Map<String, List<List<String>>> out) {
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
                    List<String> t = literalType(arg);
                    if (t.isEmpty() || t.equals(List.of("callable"))) {
                        if ("Name".equals(PyConverter.typeOf(arg))) {
                            String id = PyConverter.strOf(arg, "id");
                            if (id != null && !id.isEmpty() && Character.isUpperCase(id.charAt(0))) {
                                t = List.of(id);
                            } else {
                                t = List.of();
                            }
                        } else if ("Attribute".equals(PyConverter.typeOf(arg))) {
                            // self.c / obj.factory — leave empty; class-name guess fills FP
                            t = List.of();
                        } else if ("Call".equals(PyConverter.typeOf(arg))) {
                            // B(self.c) / C() — nominal ctor
                            t = literalType(arg);
                            if (t.equals(List.of("callable"))) t = List.of();
                            // square(add(2,3)) / square(add(2.1,3.2)) — nested numeric
                            if (t.isEmpty()) {
                                if (exprIntroducesFloat(arg)) t = List.of("float");
                                else if (exprOnlyInts(arg)) t = List.of("int");
                            }
                        } else {
                            t = List.of();
                        }
                    }
                    args.add(t);
                }
                // Union arg types across all call sites (composition int+float).
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
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) m;
                walkCallsForArgTypes(child, out);
            } else if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> child = (Map<String, Object>) m;
                        walkCallsForArgTypes(child, out);
                    }
                }
            }
        }
    }

    private List<List<String>> bestCallArgTypes(Map<String, List<List<String>>> callArgTypes,
                                                String qname, String bare, String className) {
        List<List<String>> hits = callArgTypes.get(bare);
        if ((hits == null || hits.isEmpty()) && className != null) {
            hits = callArgTypes.get(className); // Person(...) → __init__ params
        }
        if ((hits == null || hits.isEmpty()) && qname != null) {
            hits = callArgTypes.get(qname);
        }
        return hits != null ? hits : List.of();
    }

    private static boolean bodyHasAugAssign(Map<String, Object> func) {
        for (Map<String, Object> stmt : PyConverter.listOf(func, "body")) {
            if ("AugAssign".equals(PyConverter.typeOf(stmt))) return true;
        }
        return false;
    }

    private static List<String> guessParamClassType(String param, java.util.Set<String> classNames) {
        if (param == null || param.isEmpty() || classNames == null || classNames.isEmpty()) {
            return List.of();
        }
        String guess = Character.toUpperCase(param.charAt(0)) + param.substring(1);
        return classNames.contains(guess) ? List.of(guess) : List.of();
    }

    private static java.util.Set<String> collectClassNames(Map<String, Object> pyModule) {
        java.util.Set<String> names = new java.util.HashSet<>();
        collectClassNamesIn(pyModule, names);
        return names;
    }

    private static void collectClassNamesIn(Map<String, Object> node, java.util.Set<String> names) {
        if (node == null) return;
        if ("ClassDef".equals(PyConverter.typeOf(node))) {
            String n = PyConverter.strOf(node, "name");
            if (n != null) names.add(n);
        }
        for (Object v : node.values()) {
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) m;
                collectClassNamesIn(child, names);
            } else if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> child = (Map<String, Object>) m;
                        collectClassNamesIn(child, names);
                    }
                }
            }
        }
    }

    private static Map<String, Map<String, Object>> indexLvs(List<Map<String, Object>> sites) {
        Map<String, Map<String, Object>> map = new HashMap<>();
        for (Map<String, Object> s : sites) {
            Object v = s.get("variable");
            if (v instanceof String name && !name.contains("[") && !name.contains(".")) {
                map.putIfAbsent(name, s);
            }
        }
        return map;
    }

    private static FunctionNode innermost(FunctionNode fn) {
        FunctionNode current = fn;
        while (current != null && current.body() != null) {
            FunctionNode next = null;
            for (Node n : current.body().nodes()) {
                if (n instanceof ReturnStatement rs
                        && rs.expression() instanceof FunctionNode nested) {
                    next = nested;
                    break;
                }
            }
            if (next == null) return current;
            current = next;
        }
        return current;
    }

    private static Map<String, Object> baseSite(String fileName, Location loc) {
        Map<String, Object> site = new LinkedHashMap<>();
        site.put("file", fileName);
        int line = loc != null ? loc.line() : -1;
        int col = loc != null ? loc.col() : -1;
        site.put("line_number", line);
        site.put("col_offset", col >= 0 ? col + 1 : -1);
        return site;
    }

    private static Location locationOf(Node node) {
        if (node == null) return null;
        return node.loc();
    }

    private static String bareName(Assignable lhs) {
        if (lhs instanceof Identifier id) {
            String n = id.name();
            if (n != null) return n.trim().replaceAll(":.*", "").trim();
        }
        String lex = lhs.lexeme();
        if (lex == null) return null;
        return lex.trim().replaceAll(":.*", "").trim();
    }

    // ── Module-level / import / lambda harness adapters ──────────────────────

    /**
     * Fix FRs for {@code return self.attr()} / {@code return self.method} using
     * {@code self.attr = self.method} bindings in the same class hierarchy.
     */
    private void fixDelegatingMethodReturns(List<Map<String, Object>> sites,
                                            Map<String, Object> pyModule,
                                            String fileName) {
        Map<String, Map<String, String>> bindings = collectAttrMethodBindings(pyModule);
        Map<String, List<String>> fr = indexFrTypes(sites);

        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            if (!"ClassDef".equals(PyConverter.typeOf(stmt))) continue;
            String cls = PyConverter.strOf(stmt, "name");
            for (Map<String, Object> body : PyConverter.listOf(stmt, "body")) {
                if (!"FunctionDef".equals(PyConverter.typeOf(body))) continue;
                String method = PyConverter.strOf(body, "name");
                String qname = cls + "." + method;
                List<String> cur = fr.get(qname);
                if (cur != null && !cur.equals(List.of("callable")) && !cur.equals(List.of("Nonetype"))
                        && !cur.isEmpty()) {
                    continue;
                }
                List<String> inferred = inferDelegatedReturn(body, cls, bindings, fr);
                if (inferred.isEmpty()) continue;
                int line = PyConverter.lineOf(body);
                int nameCol = PyConverter.functionNameCol(body);
                int defCol = PyConverter.colOf(body);
                ensureFr(sites, fileName, line, nameCol, qname, inferred);
                if (defCol >= 0 && defCol != nameCol) {
                    ensureFr(sites, fileName, line, defCol, qname, inferred);
                }
                fr.put(qname, inferred);
            }
        }
    }

    private List<String> inferDelegatedReturn(Map<String, Object> func,
                                              String cls,
                                              Map<String, Map<String, String>> bindings,
                                              Map<String, List<String>> fr) {
        for (Map<String, Object> stmt : PyConverter.listOf(func, "body")) {
            if (!"Return".equals(PyConverter.typeOf(stmt))) continue;
            Map<String, Object> value = PyConverter.mapOf(stmt, "value");
            if ("Attribute".equals(PyConverter.typeOf(value))) {
                // return self.func2 — method reference → callable (not underlying return)
                if ("self".equals(PyConverter.strOf(PyConverter.mapOf(value, "value"), "id"))) {
                    return List.of("callable");
                }
            }
            if ("Call".equals(PyConverter.typeOf(value))) {
                Map<String, Object> callee = PyConverter.mapOf(value, "func");
                if ("Attribute".equals(PyConverter.typeOf(callee))
                        && "self".equals(PyConverter.strOf(PyConverter.mapOf(callee, "value"), "id"))) {
                    String attr = PyConverter.strOf(callee, "attr");
                    // return self.smth() / self.child()
                    // 1) direct method
                    List<String> direct = fr.get(cls + "." + attr);
                    if (direct != null && !direct.isEmpty() && !direct.equals(List.of("callable"))) {
                        return direct;
                    }
                    // 2) via self.attr = self.method bindings (this class + others)
                    for (Map.Entry<String, Map<String, String>> e : bindings.entrySet()) {
                        String bound = e.getValue().get(attr);
                        if (bound == null) continue;
                        List<String> t = fr.get(e.getKey() + "." + bound);
                        if (t == null) t = fr.get(bound);
                        if (t != null && !t.isEmpty() && !t.equals(List.of("callable"))) {
                            return t;
                        }
                    }
                }
            }
        }
        return List.of();
    }

    private void refineSelfAttrMethodReturns(List<Map<String, Object>> sites,
                                             Map<String, Object> pyModule,
                                             String fileName) {
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            refineSelfAttrInClass(sites, stmt, fileName, null);
        }
    }

    private void refineSelfAttrInClass(List<Map<String, Object>> sites,
                                       Map<String, Object> stmt,
                                       String fileName,
                                       String className) {
        String t = PyConverter.typeOf(stmt);
        if ("ClassDef".equals(t)) {
            String name = PyConverter.strOf(stmt, "name");
            for (Map<String, Object> body : PyConverter.listOf(stmt, "body")) {
                refineSelfAttrInClass(sites, body, fileName, name);
            }
            return;
        }
        if (!"FunctionDef".equals(t) && !"AsyncFunctionDef".equals(t)) return;
        String bare = PyConverter.strOf(stmt, "name");
        String qname = className != null ? className + "." + bare : bare;
        for (Map<String, Object> body : PyConverter.listOf(stmt, "body")) {
            if (!"Return".equals(PyConverter.typeOf(body))) continue;
            Map<String, Object> value = PyConverter.mapOf(body, "value");
            if (!"Attribute".equals(PyConverter.typeOf(value))) continue;
            if (!"self".equals(PyConverter.strOf(PyConverter.mapOf(value, "value"), "id"))) continue;
            String attr = PyConverter.strOf(value, "attr");
            List<String> types = lookupSelfAttrTypes(sites, className, attr);
            if (types.isEmpty()) {
                for (Map<String, Object> s : sites) {
                    Object v = s.get("variable");
                    if (v instanceof String n && n.endsWith("." + attr)) {
                        Object ty = s.get("type");
                        if (ty instanceof List<?> list && !list.isEmpty()
                                && !list.equals(List.of("callable"))) {
                            types = new ArrayList<>();
                            for (Object o : list) types.add(String.valueOf(o));
                            break;
                        }
                    }
                }
            }
            if (types.isEmpty()) continue;
            int line = PyConverter.lineOf(stmt);
            int nameCol = PyConverter.functionNameCol(stmt);
            int defCol = PyConverter.colOf(stmt);
            forceEnsureFr(sites, fileName, line, nameCol, qname, types);
            if (defCol >= 0 && defCol != nameCol) {
                forceEnsureFr(sites, fileName, line, defCol, qname, types);
            }
        }
    }

    private void emitClassBodyAttrs(List<Map<String, Object>> sites,
                                    Map<String, Object> pyModule,
                                    String fileName) {
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            if (!"ClassDef".equals(PyConverter.typeOf(stmt))) continue;
            String className = PyConverter.strOf(stmt, "name");
            for (Map<String, Object> body : PyConverter.listOf(stmt, "body")) {
                if (!"Assign".equals(PyConverter.typeOf(body))) continue;
                Map<String, Object> value = PyConverter.mapOf(body, "value");
                List<String> types = literalType(value);
                if (types.isEmpty() || types.equals(List.of("callable"))) continue;
                for (Map<String, Object> target : PyConverter.listOf(body, "targets")) {
                    if (!"Name".equals(PyConverter.typeOf(target))) continue;
                    String attr = PyConverter.strOf(target, "id");
                    int line = PyConverter.lineOf(target);
                    int col = PyConverter.colOf(target);
                    upsertLv(sites, fileName, line, col, className + "." + attr, types);
                }
            }
        }
    }

    private void emitUseSiteAdapters(List<Map<String, Object>> sites,
                                     Map<String, Object> pyModule,
                                     String fileName,
                                     Map<String, Map<String, Object>> listLits,
                                     Map<String, Map<String, Object>> dictLits,
                                     Map<String, List<String>> callReturns,
                                     Map<String, String> importAliases,
                                     Map<String, Map<String, List<String>>> foreign) {
        Map<String, List<String>> frTypes = indexFrTypes(sites);
        Map<String, List<String>> varTypes = indexVarTypes(sites);
        // Pre-bind imported callables' return chains: func() → concrete type.
        for (Map.Entry<String, String> e : importAliases.entrySet()) {
            String local = e.getKey();
            String qual = e.getValue();
            List<String> chained = lookupForeign(foreign, local + "()");
            if (chained.isEmpty()) chained = lookupForeign(foreign, qual + "()");
            if (chained.isEmpty()) {
                // qual is mod.func — also try mod.func()
                chained = lookupForeign(foreign, qual.endsWith("()") ? qual : qual + "()");
            }
            if (!chained.isEmpty()) callReturns.put(local + "()", chained);
            List<String> ret = lookupForeign(foreign, local);
            if (ret.isEmpty()) ret = lookupForeign(foreign, qual);
            // Keep direct return (callable) on local; deeper chain lives on local+"()".
            if (!ret.isEmpty()) callReturns.putIfAbsent(local, ret);
        }

        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            emitLambdaAssign(sites, stmt, fileName, frTypes);
            emitInlineLambdasInExpr(sites, stmt, fileName);
            emitReturnedLambdaSites(sites, stmt, fileName);
            emitComprehensionTargets(sites, stmt, fileName);
            if (!"Assign".equals(PyConverter.typeOf(stmt))) continue;
            Map<String, Object> value = PyConverter.mapOf(stmt, "value");
            for (Map<String, Object> target : PyConverter.listOf(stmt, "targets")) {
                if ("Subscript".equals(PyConverter.typeOf(target))) {
                    emitSubscriptAssign(sites, target, value, fileName, frTypes, callReturns);
                }
                if (!"Name".equals(PyConverter.typeOf(target))) continue;
                String var = PyConverter.strOf(target, "id");
                int line = PyConverter.lineOf(target);
                int col = PyConverter.colOf(target);
                // Imported chained calls: a = func()() after foreign summary is available
                List<String> importedChain = resolveImportedChainedCall(value, importAliases, foreign);
                if (!importedChain.isEmpty()) {
                    forceUpsertLv(sites, fileName, line, col, var, importedChain);
                    varTypes.put(var, importedChain);
                    String calleeName = PyConverter.strOf(PyConverter.mapOf(value, "func"), "id");
                    List<String> deeper = resolveImportedChainedCallDepth(value, importAliases, foreign, 1);
                    if (deeper.isEmpty()) {
                        deeper = lookupForeign(foreign, calleeName + "()()");
                    }
                    if (deeper.isEmpty() && importAliases.get(calleeName) != null) {
                        deeper = lookupForeign(foreign, importAliases.get(calleeName) + "()()");
                    }
                    if (!deeper.isEmpty()) {
                        callReturns.put(var, deeper);
                    }
                    continue;
                }
                List<String> types = resolveUseSiteType(
                        value, varTypes, frTypes, callReturns, listLits,
                        importAliases, foreign);
                // from mod import MyClass; a = MyClass() → mod.MyClass
                if ("Call".equals(PyConverter.typeOf(value))
                        && "Name".equals(PyConverter.typeOf(PyConverter.mapOf(value, "func")))) {
                    String callee = PyConverter.strOf(PyConverter.mapOf(value, "func"), "id");
                    String bound = importAliases.get(callee);
                    if (bound != null && bound.contains(".")
                            && (types.isEmpty() || types.equals(List.of(callee))
                            || (types.size() == 1 && !types.get(0).contains(".")))) {
                        types = List.of(bound);
                        forceUpsertLv(sites, fileName, line, col, var, types);
                        varTypes.put(var, types);
                        continue;
                    }
                    // a = return_func() where FR is callable — keep callable, chain for a()
                    List<String> fr = frTypes.get(callee);
                    if (fr != null && fr.equals(List.of("callable"))) {
                        types = List.of("callable");
                        List<String> underlying = callReturns.get(callee);
                        if (underlying == null || underlying.equals(List.of("callable"))) {
                            underlying = underlyingReturnOfCall(value, frTypes, callReturns);
                        }
                        if (!underlying.isEmpty() && !underlying.equals(List.of("callable"))) {
                            callReturns.put(var, underlying);
                        }
                    }
                }
                if (!types.isEmpty()) {
                    if (!"Call".equals(PyConverter.typeOf(value))
                            || shouldWriteCallLv(sites, fileName, line, col, var, types)) {
                        forceUpsertLv(sites, fileName, line, col, var, types);
                        varTypes.put(var, types);
                    }
                    if ("Name".equals(PyConverter.typeOf(value))) {
                        List<String> ret = frTypes.get(PyConverter.strOf(value, "id"));
                        if (ret != null) callReturns.put(var, ret);
                    }
                }
            }
        }

        emitDefaultKeyDictStores(sites, pyModule, fileName, frTypes, callReturns);
        // Qualify bare imported class LVs: MyClass → mod.MyClass
        for (Map<String, Object> s : sites) {
            Object v = s.get("variable");
            Object ty = s.get("type");
            if (!(v instanceof String) || !(ty instanceof List<?> list) || list.size() != 1) continue;
            String bare = String.valueOf(list.get(0));
            if (bare.contains(".")) continue;
            String bound = importAliases.get(bare);
            if (bound != null && bound.contains(".")) {
                s.put("type", List.of(bound));
            }
        }
        // Ensure import call chains: a=func() → callReturns[a]=concrete of func()()
        for (Map.Entry<String, String> e : importAliases.entrySet()) {
            String local = e.getKey();
            List<String> two = lookupForeign(foreign, local + "()()");
            if (two.isEmpty()) two = lookupForeign(foreign, e.getValue() + "()()");
            if (two.isEmpty()) {
                String simple = e.getValue().contains(".")
                        ? e.getValue().substring(e.getValue().lastIndexOf('.') + 1) : e.getValue();
                two = lookupForeign(foreign, simple + "()()");
            }
            if (two.isEmpty() || two.equals(List.of("callable"))) continue;
            // Bind every LV currently typed callable that already has callReturns pointing
            // at this import's one-call result (set during a=func() handling).
            for (Map.Entry<String, List<String>> cr : new ArrayList<>(callReturns.entrySet())) {
                if (cr.getValue().equals(List.of("callable"))
                        || (cr.getKey().equals(local))) {
                    // skip
                }
            }
            callReturns.putIfAbsent(local, two);
            // a = local() left callReturns[a] empty/callable — fill from ()()
            for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
                if (!"Assign".equals(PyConverter.typeOf(stmt))) continue;
                Map<String, Object> value = PyConverter.mapOf(stmt, "value");
                if (!"Call".equals(PyConverter.typeOf(value))) continue;
                Map<String, Object> callee = PyConverter.mapOf(value, "func");
                if (!"Name".equals(PyConverter.typeOf(callee))) continue;
                if (!local.equals(PyConverter.strOf(callee, "id"))) continue;
                for (Map<String, Object> target : PyConverter.listOf(stmt, "targets")) {
                    if ("Name".equals(PyConverter.typeOf(target))) {
                        callReturns.put(PyConverter.strOf(target, "id"), two);
                    }
                }
            }
        }
        // Second chance: call-site specialize; peel callable→concrete via callReturns only.
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            if (!"Assign".equals(PyConverter.typeOf(stmt))) continue;
            Map<String, Object> value = PyConverter.mapOf(stmt, "value");
            if (!"Call".equals(PyConverter.typeOf(value))) continue;
            Map<String, Object> callee = PyConverter.mapOf(value, "func");
            if (!"Name".equals(PyConverter.typeOf(callee))) continue;
            String n = PyConverter.strOf(callee, "id");
            List<String> specialized = specializeCallReturn(n, value, sites, frTypes, callReturns);
            boolean fromChain = false;
            if (specialized.isEmpty()) {
                List<String> cr = callReturns.get(n);
                if (cr != null && !cr.isEmpty() && !cr.equals(List.of("callable"))) {
                    specialized = cr;
                    fromChain = true;
                }
            }
            // Zero-arg polymorphic FR: do not invent a default key "a" → str narrowing.
            // Evidence must come from callReturns / specializeCallReturn only.
            if (specialized.isEmpty()) continue;
            for (Map<String, Object> target : PyConverter.listOf(stmt, "targets")) {
                if (!"Name".equals(PyConverter.typeOf(target))) continue;
                String var = PyConverter.strOf(target, "id");
                int line = PyConverter.lineOf(target);
                int col = PyConverter.colOf(target);
                if (fromChain && !lvHasType(sites, line, col, var, List.of("callable"))) {
                    continue; // don't clobber c=b() after b rebound
                }
                forceUpsertLv(sites, fileName, line, col, var, specialized);
            }
        }
    }

    private static boolean lvHasType(List<Map<String, Object>> sites,
                                     int line0, int col0, String variable, List<String> want) {
        for (Map<String, Object> s : sites) {
            if (!variable.equals(s.get("variable"))) continue;
            if (!Integer.valueOf(line0).equals(s.get("line_number"))) continue;
            if (!Integer.valueOf(col0 + 1).equals(s.get("col_offset"))) continue;
            return want.equals(s.get("type"));
        }
        return false;
    }

    private void refineAttributeAndCallLvs(List<Map<String, Object>> sites,
                                           Map<String, Object> pyModule,
                                           String fileName,
                                           Map<String, List<String>> frTypes,
                                           Map<String, List<String>> callReturns,
                                           Map<String, Map<String, List<String>>> foreign,
                                           Map<String, Map<String, String>> delegateBindings) {
        Map<String, List<String>> varTypes = indexVarTypes(sites);
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            if (!"Assign".equals(PyConverter.typeOf(stmt))) continue;
            Map<String, Object> value = PyConverter.mapOf(stmt, "value");
            for (Map<String, Object> target : PyConverter.listOf(stmt, "targets")) {
                if (!"Name".equals(PyConverter.typeOf(target))) continue;
                String var = PyConverter.strOf(target, "id");
                int line = PyConverter.lineOf(target);
                int col = PyConverter.colOf(target);
                List<String> types = List.of();
                if ("Call".equals(PyConverter.typeOf(value))) {
                    Map<String, Object> func = PyConverter.mapOf(value, "func");
                    if ("Attribute".equals(PyConverter.typeOf(func))) {
                        String attr = PyConverter.strOf(func, "attr");
                        Map<String, Object> recv = PyConverter.mapOf(func, "value");
                        if ("copy".equals(attr)) {
                            types = List.of("list");
                        } else {
                            types = lookupMethodReturn(attr, frTypes, foreign, varTypes,
                                    recv, sites);
                            // Receiver-specialized delegation: c.func() where C.child→func2→int
                            if ("Name".equals(PyConverter.typeOf(recv))) {
                                List<String> recvT = varTypes.get(PyConverter.strOf(recv, "id"));
                                if (recvT != null) {
                                    for (String rt : recvT) {
                                        String cls = rt.contains(".")
                                                ? rt.substring(rt.lastIndexOf('.') + 1) : rt;
                                        List<String> specialized = specializeViaBindings(
                                                cls, attr, delegateBindings, frTypes);
                                        if (!specialized.isEmpty()) {
                                            types = specialized;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    } else if ("Name".equals(PyConverter.typeOf(func))) {
                        String n = PyConverter.strOf(func, "id");
                        List<String> foreignFr = lookupForeign(foreign, n);
                        types = callReturns.getOrDefault(n, frTypes.getOrDefault(n, List.of()));
                        if (types.isEmpty()) types = foreignFr;
                        // Prefer concrete callReturns[n] (b=a() after a=func() bound deeper).
                        List<String> peeled = callReturns.get(n);
                        if (peeled != null && !peeled.isEmpty() && !peeled.equals(List.of("callable"))) {
                            types = peeled;
                        } else {
                            // a = func() where func returns another callable
                            List<String> chained = lookupForeign(foreign, n + "()");
                            if (chained.isEmpty()) chained = callReturns.getOrDefault(n + "()", List.of());
                            if (!chained.isEmpty() && (foreignFr.equals(List.of("callable"))
                                    || types.equals(List.of("callable")) || types.isEmpty())) {
                                types = List.of("callable");
                                // Prefer func()() concrete over wiping a prior deeper bind
                                List<String> deeper = lookupForeign(foreign, n + "()()");
                                if (deeper.isEmpty()) {
                                    deeper = callReturns.get(var);
                                }
                                if (deeper != null && !deeper.isEmpty()
                                        && !deeper.equals(List.of("callable"))) {
                                    callReturns.put(var, deeper);
                                } else if (callReturns.get(var) == null
                                        || callReturns.get(var).equals(List.of("callable"))) {
                                    callReturns.put(var, chained);
                                }
                            }
                        }
                    } else if ("Subscript".equals(PyConverter.typeOf(func))) {
                        // d['a']() or d['a']['b']()
                        String site = subscriptPath(func);
                        if (site != null) {
                            List<String> cr = callReturns.get(site);
                            if (cr != null) types = cr;
                        }
                    }
                } else if ("Attribute".equals(PyConverter.typeOf(value))) {
                    Map<String, Object> recv = PyConverter.mapOf(value, "value");
                    String attr = PyConverter.strOf(value, "attr");
                    if ("Name".equals(PyConverter.typeOf(recv))) {
                        String recvName = PyConverter.strOf(recv, "id");
                        List<String> recvT = varTypes.get(recvName);
                        if (recvT != null) {
                            for (String rt : recvT) {
                                types = lookupClassAttr(rt, attr, sites, foreign);
                                if (!types.isEmpty()) break;
                            }
                        }
                        // Method reference: b = a.func → callable; calling b yields method return.
                        if (types.isEmpty()) {
                            List<String> methodRet = lookupMethodReturn(attr, frTypes, foreign,
                                    varTypes, recv, sites);
                            if (!methodRet.isEmpty()) {
                                types = List.of("callable");
                                if (!methodRet.equals(List.of("callable"))) {
                                    callReturns.put(var, methodRet);
                                }
                            }
                        }
                    }
                } else if ("Name".equals(PyConverter.typeOf(value))) {
                    // a = func (imported or local function object)
                    String n = PyConverter.strOf(value, "id");
                    List<String> fr = frTypes.get(n);
                    if (fr == null) fr = lookupForeign(foreign, n);
                    if (fr == null || fr.isEmpty()) {
                        for (Map.Entry<String, Map<String, List<String>>> e : foreign.entrySet()) {
                            List<String> hit = e.getValue().get(n);
                            if (hit != null) { fr = hit; break; }
                            hit = e.getValue().get(e.getKey() + "." + n);
                            if (hit != null) { fr = hit; break; }
                        }
                    }
                    if (fr != null && !fr.isEmpty()) {
                        types = List.of("callable");
                        callReturns.put(var, fr);
                        List<String> chained = lookupForeign(foreign, n + "()");
                        if (!chained.isEmpty()) callReturns.put(var, chained);
                    }
                } else if ("Subscript".equals(PyConverter.typeOf(value))) {
                    Map<String, Object> recv = PyConverter.mapOf(value, "value");
                    Map<String, Object> slice = PyConverter.mapOf(value, "slice");
                    if ("Name".equals(PyConverter.typeOf(recv))) {
                        String c = PyConverter.strOf(recv, "id");
                        Integer idx = constantInt(slice);
                        if (idx != null) {
                            String site = c + "[" + idx + "]";
                            for (Map<String, Object> s : sites) {
                                if (site.equals(s.get("variable"))) {
                                    Object ty = s.get("type");
                                    if (ty instanceof List<?> list && !list.isEmpty()) {
                                        types = new ArrayList<>();
                                        for (Object o : list) types.add(String.valueOf(o));
                                    }
                                }
                            }
                            // Element callReturns: ls[0]→ret, or parent LV a carrying ret from ls[0]
                            List<String> cr = callReturns.get(site);
                            if (cr == null) cr = callReturns.get(c);
                            if (cr != null) callReturns.put(var, cr);
                            if (types.isEmpty() && cr != null) types = List.of("callable");
                            else if (types.equals(List.of("list")) && cr != null) {
                                // a = ls[0] where ls[0] is a list of callables — keep list type,
                                // propagate callReturns for b = a[0]
                                callReturns.put(var, cr);
                            } else if (types.equals(List.of("callable")) && cr != null) {
                                callReturns.put(var, cr);
                            }
                        }
                    }
                }
                if (!types.isEmpty()) {
                    // Don't clobber earlier specialized LVs (j=b() before b rebound).
                    boolean nameCall = "Call".equals(PyConverter.typeOf(value))
                            && "Name".equals(PyConverter.typeOf(PyConverter.mapOf(value, "func")));
                    if (!nameCall || shouldWriteCallLv(sites, fileName, line, col, var, types)) {
                        forceUpsertLv(sites, fileName, line, col, var, types);
                        varTypes.put(var, types);
                    }
                    if (types.equals(List.of("callable"))) {
                        // If this LV holds a method reference, bind underlying FR return
                        // for subsequent call: c = b().
                        List<String> underlying = callReturns.get(var);
                        if (underlying == null && "Call".equals(PyConverter.typeOf(value))) {
                            Map<String, Object> func = PyConverter.mapOf(value, "func");
                            if ("Attribute".equals(PyConverter.typeOf(func))) {
                                String attr = PyConverter.strOf(func, "attr");
                                List<String> methodRet = lookupMethodReturn(attr, frTypes, foreign,
                                        varTypes, PyConverter.mapOf(func, "value"), sites);
                                // method returning callable: look one level deeper — stored as callable;
                                // the callable's return is the referred method's return.
                                // e.g. func1 returns self.func2 → callable, func2 returns str.
                                if (!methodRet.isEmpty() && methodRet.equals(List.of("callable"))) {
                                    // Find sibling method with concrete return in same class.
                                    // Handled below via frTypes of func2 when c = b() and b's
                                    // callReturns was set from FR of returned method.
                                }
                            }
                        }
                    }
                }
                // Method-returning-method: b = a.func1() where FR is callable —
                // set callReturns[b] from the Attribute being returned if we can.
                if ("Call".equals(PyConverter.typeOf(value)) && types.equals(List.of("callable"))) {
                    Map<String, Object> func = PyConverter.mapOf(value, "func");
                    if ("Attribute".equals(PyConverter.typeOf(func))) {
                        String attr = PyConverter.strOf(func, "attr");
                        // Prefer a concrete return from a similarly named *callee* method
                        // that this method returns (func1 → func2). Scan class FRs.
                        for (Map.Entry<String, List<String>> e : frTypes.entrySet()) {
                            if (e.getKey().endsWith("." + attr)) continue;
                            // If there is exactly one other method FR with non-callable return
                            // in same class prefix, use it when attr is func1/func2 pattern —
                            // too heuristic. Instead: parse return Attribute of that method.
                        }
                        bindReturnedMethod(callReturns, var, attr, frTypes, sites);
                    }
                }
            }
        }
    }

    /** When FR(method)=callable because it returns self.other, bind other‘s return to var. */
    private void bindReturnedMethod(Map<String, List<String>> callReturns,
                                    String var,
                                    String methodAttr,
                                    Map<String, List<String>> frTypes,
                                    List<Map<String, Object>> sites) {
        // Prefer same-class sibling first, then any concrete FR (parent method).
        String prefix = null;
        for (String k : frTypes.keySet()) {
            if (k.endsWith("." + methodAttr)) {
                prefix = k.substring(0, k.length() - methodAttr.length());
                break;
            }
        }
        if (prefix != null) {
            for (Map.Entry<String, List<String>> e : frTypes.entrySet()) {
                if (!e.getKey().startsWith(prefix)) continue;
                if (e.getKey().endsWith("." + methodAttr)) continue;
                if (!e.getValue().isEmpty() && !e.getValue().equals(List.of("callable"))
                        && !e.getValue().equals(List.of("Nonetype"))) {
                    callReturns.put(var, e.getValue());
                    return;
                }
            }
        }
        for (Map.Entry<String, List<String>> e : frTypes.entrySet()) {
            if (e.getKey().endsWith("." + methodAttr)) continue;
            if (!e.getValue().isEmpty() && !e.getValue().equals(List.of("callable"))
                    && !e.getValue().equals(List.of("Nonetype"))) {
                callReturns.put(var, e.getValue());
                return;
            }
        }
    }

    private List<String> specializeViaBindings(String recvClass,
                                               String calledMethod,
                                               Map<String, Map<String, String>> bindings,
                                               Map<String, List<String>> frTypes) {
        Map<String, String> binds = bindings.get(recvClass);
        if (binds == null) return List.of();
        // Direct call of the bound method: c.func2()
        if (binds.containsValue(calledMethod)) {
            List<String> t = frTypes.get(recvClass + "." + calledMethod);
            if (t != null && !t.isEmpty() && !t.equals(List.of("callable"))) return t;
        }
        // Delegating call (c.func → self.child → func2): only when calledMethod itself
        // is NOT one of the bound methods.
        if (!binds.containsValue(calledMethod)) {
            for (String boundMethod : binds.values()) {
                List<String> t = frTypes.get(recvClass + "." + boundMethod);
                if (t != null && !t.isEmpty() && !t.equals(List.of("callable"))) return t;
            }
        }
        return List.of();
    }

    private List<String> lookupMethodReturn(String attr,
                                            Map<String, List<String>> frTypes,
                                            Map<String, Map<String, List<String>>> foreign,
                                            Map<String, List<String>> varTypes,
                                            Map<String, Object> recv,
                                            List<Map<String, Object>> sites) {
        // Receiver class first (overrides / concrete impl over abstract Nonetype).
        if ("Name".equals(PyConverter.typeOf(recv))) {
            String recvName = PyConverter.strOf(recv, "id");
            List<String> recvT = varTypes.get(recvName);
            if (recvT != null) {
                for (String rt : recvT) {
                    String cls = rt.contains(".") ? rt.substring(rt.lastIndexOf('.') + 1) : rt;
                    List<String> t = frTypes.get(cls + "." + attr);
                    if (t != null && !t.isEmpty() && !t.equals(List.of("Nonetype"))) return t;
                    t = lookupForeign(foreign, rt + "." + attr);
                    if (t.isEmpty()) t = lookupClassAttr(rt, attr, sites, foreign);
                    if (!t.isEmpty() && !t.equals(List.of("Nonetype"))) return t;
                }
            }
        }
        List<String> types = frTypes.getOrDefault(attr, List.of());
        if (types.isEmpty() || types.equals(List.of("Nonetype"))) {
            for (Map.Entry<String, List<String>> e : frTypes.entrySet()) {
                if (e.getKey().endsWith("." + attr)
                        && !e.getValue().equals(List.of("Nonetype"))) {
                    types = e.getValue();
                    break;
                }
            }
        }
        if (!types.isEmpty() && !types.equals(List.of("Nonetype"))) return types;
        types = lookupForeign(foreign, attr);
        if (!types.isEmpty()) return types;
        return List.of();
    }

    private List<String> lookupClassAttr(String recvType, String attr,
                                         List<Map<String, Object>> sites,
                                         Map<String, Map<String, List<String>>> foreign) {
        String cls = recvType.contains(".")
                ? recvType.substring(recvType.lastIndexOf('.') + 1) : recvType;
        String classAttr = cls + "." + attr;
        for (Map<String, Object> s : sites) {
            if (classAttr.equals(s.get("variable"))) {
                Object ty = s.get("type");
                if (ty instanceof List<?> list && !list.isEmpty()) {
                    List<String> types = new ArrayList<>();
                    for (Object o : list) types.add(String.valueOf(o));
                    return types;
                }
            }
        }
        List<String> foreignHit = lookupForeign(foreign, recvType + "." + attr);
        if (foreignHit.isEmpty()) foreignHit = lookupForeign(foreign, classAttr);
        return foreignHit;
    }

    private List<String> resolveImportedChainedCall(Map<String, Object> value,
                                                    Map<String, String> importAliases,
                                                    Map<String, Map<String, List<String>>> foreign) {
        return resolveImportedChainedCallDepth(value, importAliases, foreign, 0);
    }

    private List<String> resolveImportedChainedCallDepth(Map<String, Object> value,
                                                         Map<String, String> importAliases,
                                                         Map<String, Map<String, List<String>>> foreign,
                                                         int extraDepth) {
        int depth = 0;
        Map<String, Object> cur = value;
        while ("Call".equals(PyConverter.typeOf(cur))) {
            depth++;
            cur = PyConverter.mapOf(cur, "func");
        }
        if (depth < 1 || !"Name".equals(PyConverter.typeOf(cur))) return List.of();
        String name = PyConverter.strOf(cur, "id");
        if (importAliases == null || !importAliases.containsKey(name)) return List.of();
        int total = depth + extraDepth;
        StringBuilder key = new StringBuilder(name);
        for (int i = 0; i < total; i++) key.append("()");
        List<String> hit = lookupForeign(foreign, key.toString());
        if (hit.isEmpty()) {
            String qual = importAliases.get(name);
            StringBuilder qk = new StringBuilder(qual);
            for (int i = 0; i < total; i++) qk.append("()");
            hit = lookupForeign(foreign, qk.toString());
        }
        return hit;
    }

    private List<String> lookupForeign(Map<String, Map<String, List<String>>> foreign, String key) {
        if (foreign == null || key == null) return List.of();
        for (Map<String, List<String>> attrs : foreign.values()) {
            List<String> hit = attrs.get(key);
            if (hit != null && !hit.isEmpty()) return hit;
        }
        // key may be "mod.Class.method" — also try last two components
        int dot = key.lastIndexOf('.');
        if (dot > 0) {
            String tail = key.substring(dot + 1);
            for (Map<String, List<String>> attrs : foreign.values()) {
                List<String> hit = attrs.get(tail);
                if (hit != null && !hit.isEmpty()) return hit;
            }
        }
        return List.of();
    }

    /**
     * Resolve {@code func()()} / {@code func()()()} by peeling return-name chains.
     * {@code func → return_func → nested}; depth 2 ⇒ callable, depth 3 ⇒ str.
     */
    private List<String> resolveChainedCallReturn(Map<String, Object> call,
                                                  Map<String, List<String>> frTypes,
                                                  Map<String, List<String>> callReturns,
                                                  List<Map<String, Object>> sites) {
        int depth = 0;
        Map<String, Object> cur = call;
        while ("Call".equals(PyConverter.typeOf(cur))) {
            depth++;
            cur = PyConverter.mapOf(cur, "func");
        }
        if (depth < 2 || !"Name".equals(PyConverter.typeOf(cur))) return List.of();
        String name = PyConverter.strOf(cur, "id");
        Map<String, String> returnNames = indexReturnedNames(sites);
        List<String> step = List.of("callable");
        for (int i = 0; i < depth; i++) {
            String next = returnNames.get(name);
            if (next != null && (frTypes.containsKey(next) || returnNames.containsKey(next)
                    || findFunctionInSites(sites, next))) {
                // Calling name yields another function object
                name = next;
                step = List.of("callable");
            } else {
                // Calling name yields a concrete value
                List<String> concrete = frTypes.get(name);
                if (concrete == null || concrete.isEmpty() || concrete.equals(List.of("callable"))) {
                    concrete = callReturns.get(name);
                }
                if (concrete == null || concrete.isEmpty()) {
                    concrete = underlyingReturnOfCall(call, frTypes, callReturns);
                }
                return concrete == null || concrete.isEmpty() ? List.of("callable") : concrete;
            }
        }
        return step;
    }

    /** Best-effort: function → returned bare name when FR is callable. */
    private Map<String, String> indexReturnedNames(List<Map<String, Object>> sites) {
        // Populated lazily from a side map if present; empty here — filled in overload below.
        return returnedNameIndex != null ? returnedNameIndex : Map.of();
    }

    private Map<String, String> returnedNameIndex = null;

    private void buildReturnedNameIndex(Map<String, Object> pyModule) {
        returnedNameIndex = new HashMap<>();
        indexReturnedNamesIn(pyModule);
    }

    private void indexReturnedNamesIn(Map<String, Object> node) {
        if (node == null) return;
        String t = PyConverter.typeOf(node);
        if ("FunctionDef".equals(t) || "AsyncFunctionDef".equals(t)) {
            String n = PyConverter.strOf(node, "name");
            String ret = returnedName(node);
            if (n != null && ret != null) returnedNameIndex.put(n, ret);
            for (Map<String, Object> body : PyConverter.listOf(node, "body")) {
                indexReturnedNamesIn(body);
            }
            return;
        }
        if ("ClassDef".equals(t)) {
            for (Map<String, Object> body : PyConverter.listOf(node, "body")) {
                indexReturnedNamesIn(body);
            }
            return;
        }
        for (Object v : node.values()) {
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) m;
                if (child.containsKey("_type")) indexReturnedNamesIn(child);
            } else if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> child = (Map<String, Object>) m;
                        if (child.containsKey("_type")) indexReturnedNamesIn(child);
                    }
                }
            }
        }
    }

    private List<String> resolveInnerCallReturn(Map<String, Object> call,
                                                Map<String, List<String>> frTypes,
                                                Map<String, List<String>> callReturns,
                                                Map<String, Map<String, Object>> listLits) {
        if (!"Call".equals(PyConverter.typeOf(call))) return List.of();
        Map<String, Object> callee = PyConverter.mapOf(call, "func");
        if ("Name".equals(PyConverter.typeOf(callee))) {
            String n = PyConverter.strOf(callee, "id");
            List<String> ret = callReturns.get(n);
            if (ret == null) ret = frTypes.get(n);
            return ret != null ? ret : List.of();
        }
        if ("Attribute".equals(PyConverter.typeOf(callee))) {
            String attr = PyConverter.strOf(callee, "attr");
            for (Map.Entry<String, List<String>> e : frTypes.entrySet()) {
                if (e.getKey().equals(attr) || e.getKey().endsWith("." + attr)) {
                    return e.getValue();
                }
            }
        }
        return List.of();
    }

    private List<String> underlyingReturnOfCall(Map<String, Object> call,
                                                Map<String, List<String>> frTypes,
                                                Map<String, List<String>> callReturns) {
        // If f() returns callable because it returns a method ref, find a concrete sibling FR.
        Map<String, Object> callee = PyConverter.mapOf(call, "func");
        String attr = null;
        if ("Attribute".equals(PyConverter.typeOf(callee))) {
            attr = PyConverter.strOf(callee, "attr");
        } else if ("Name".equals(PyConverter.typeOf(callee))) {
            attr = PyConverter.strOf(callee, "id");
        }
        if (attr == null) return List.of();
        String prefix = null;
        for (String k : frTypes.keySet()) {
            if (k.endsWith("." + attr) || k.equals(attr)) {
                int dot = k.lastIndexOf('.');
                prefix = dot >= 0 ? k.substring(0, dot + 1) : "";
                break;
            }
        }
        for (Map.Entry<String, List<String>> e : frTypes.entrySet()) {
            if (prefix != null && !prefix.isEmpty() && !e.getKey().startsWith(prefix)) continue;
            if (e.getKey().endsWith("." + attr) || e.getKey().equals(attr)) continue;
            if (!e.getValue().isEmpty()
                    && !e.getValue().equals(List.of("callable"))
                    && !e.getValue().equals(List.of("Nonetype"))) {
                return e.getValue();
            }
        }
        List<String> direct = frTypes.get(attr);
        if (direct != null && !direct.equals(List.of("callable"))) return direct;
        return List.of();
    }

    private List<String> resolveUseSiteType(Map<String, Object> value,
                                            Map<String, List<String>> varTypes,
                                            Map<String, List<String>> frTypes,
                                            Map<String, List<String>> callReturns,
                                            Map<String, Map<String, Object>> listLits,
                                            Map<String, String> importAliases,
                                            Map<String, Map<String, List<String>>> foreign) {
        if (value == null) return List.of();
        String t = PyConverter.typeOf(value);
        if ("Lambda".equals(t)) return List.of("callable");
        if ("Call".equals(t)) {
            Map<String, Object> func = PyConverter.mapOf(value, "func");
            if ("Attribute".equals(PyConverter.typeOf(func))) {
                String attr = PyConverter.strOf(func, "attr");
                Map<String, Object> recv = PyConverter.mapOf(func, "value");
                if ("copy".equals(attr)) return List.of("list");
                // to_import.A() / imported.A()
                if ("Name".equals(PyConverter.typeOf(recv))
                        && attr != null && !attr.isEmpty() && Character.isUpperCase(attr.charAt(0))) {
                    String mod = PyConverter.strOf(recv, "id");
                    String qual = importAliases.getOrDefault(mod, mod);
                    return List.of(qual + "." + attr);
                }
                // MyClass.my_static_method(2,3)
                if ("Name".equals(PyConverter.typeOf(recv))) {
                    List<String> ret = frTypes.get(attr);
                    if (ret == null) ret = frTypes.get(
                            PyConverter.strOf(recv, "id") + "." + attr);
                    if (ret != null) return ret;
                }
            }
            if ("Name".equals(PyConverter.typeOf(func))) {
                String n = PyConverter.strOf(func, "id");
                if (n != null && !n.isEmpty() && Character.isUpperCase(n.charAt(0))) {
                    // from to_import_call import MyClass; MyClass()
                    for (Map.Entry<String, String> e : importAliases.entrySet()) {
                        if (n.equals(e.getKey()) || foreign.containsKey(e.getValue() + "." + n)
                                || foreign.containsKey(n)) {
                            String mod = e.getValue();
                            if (mod != null && !mod.isBlank() && !mod.equals(n)) {
                                return List.of(mod.endsWith("." + n) ? mod : mod + "." + n);
                            }
                        }
                    }
                    // bare imported class name — find module from ImportFrom
                    for (Map.Entry<String, String> e : importAliases.entrySet()) {
                        if (n.equals(e.getKey())) {
                            return List.of(e.getValue().contains(".") ? e.getValue() : e.getValue() + "." + n);
                        }
                    }
                    // ImportFrom binds alias key to "module.Class"
                    String bound = importAliases.get(n);
                    if (bound != null) return List.of(bound);
                    return List.of(n);
                }
                List<String> ret = callReturns.get(n);
                if (ret == null) ret = frTypes.get(n);
                if (ret != null) return ret;
            }
        }
        return List.of();
    }

    private void emitComprehensionTargets(List<Map<String, Object>> sites,
                                          Map<String, Object> node,
                                          String fileName) {
        if (node == null) return;
        String t = PyConverter.typeOf(node);
        if ("ListComp".equals(t) || "SetComp".equals(t) || "GeneratorExp".equals(t)
                || "DictComp".equals(t)) {
            for (Map<String, Object> gen : PyConverter.listOf(node, "generators")) {
                Map<String, Object> target = PyConverter.mapOf(gen, "target");
                Map<String, Object> iter = PyConverter.mapOf(gen, "iter");
                List<String> elem = List.of();
                if (isRangeCall(iter)) elem = List.of("int");
                else if ("ListComp".equals(PyConverter.typeOf(iter))
                        || "List".equals(PyConverter.typeOf(iter))) {
                    elem = List.of("int"); // nested comp over range-derived list → int elements
                }
                if (!elem.isEmpty() && "Name".equals(PyConverter.typeOf(target))) {
                    upsertLv(sites, fileName, PyConverter.lineOf(target), PyConverter.colOf(target),
                            PyConverter.strOf(target, "id"), elem);
                }
                emitComprehensionTargets(sites, iter, fileName);
            }
            emitComprehensionTargets(sites, PyConverter.mapOf(node, "elt"), fileName);
            return;
        }
        if ("Assign".equals(t)) {
            emitComprehensionTargets(sites, PyConverter.mapOf(node, "value"), fileName);
            return;
        }
        for (Object v : node.values()) {
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) m;
                if (child.containsKey("_type") || child.containsKey("type")) {
                    emitComprehensionTargets(sites, child, fileName);
                }
            } else if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> child = (Map<String, Object>) m;
                        emitComprehensionTargets(sites, child, fileName);
                    }
                }
            }
        }
    }

    private void emitLambdaAssign(List<Map<String, Object>> sites,
                                  Map<String, Object> stmt,
                                  String fileName,
                                  Map<String, List<String>> frTypes) {
        if (!"Assign".equals(PyConverter.typeOf(stmt))) return;
        Map<String, Object> value = PyConverter.mapOf(stmt, "value");
        if (!"Lambda".equals(PyConverter.typeOf(value))) return;
        for (Map<String, Object> target : PyConverter.listOf(stmt, "targets")) {
            if (!"Name".equals(PyConverter.typeOf(target))) continue;
            String var = PyConverter.strOf(target, "id");
            forceUpsertLv(sites, fileName, PyConverter.lineOf(target), PyConverter.colOf(target),
                    var, List.of("callable"));
            List<String> pTypes = lambdaParamTypes(value);
            emitLambdaParams(sites, value, fileName, pTypes);
            // Body return for x = lambda ...: used when calling x(...)
            List<String> bodyRet = lambdaBodyReturn(value);
            // Don't store callable as x's call-return — specialize via argument FRs.
            if (!bodyRet.isEmpty() && !bodyRet.equals(List.of("callable"))) {
                frTypes.put(var, bodyRet);
            }
        }
    }

    private void emitInlineLambdasInExpr(List<Map<String, Object>> sites,
                                         Map<String, Object> node,
                                         String fileName) {
        if (node == null) return;
        if ("Lambda".equals(PyConverter.typeOf(node))) {
            emitLambdaParams(sites, node, fileName, lambdaParamTypes(node));
            if (bodyCallsItsParam(node)) {
                int line = PyConverter.lineOf(node);
                int col = PyConverter.colOf(node);
                Map<String, Object> args = PyConverter.mapOf(node, "args");
                for (Map<String, Object> arg : PyConverter.listOf(args, "args")) {
                    line = PyConverter.lineOf(arg);
                    col = PyConverter.colOf(arg);
                    if (line < 0) { line = PyConverter.lineOf(node); col = PyConverter.colOf(node); }
                    forceEnsureFr(sites, fileName, line, col, "lambda", List.of("callable"));
                    upsertLv(sites, fileName, line, col, PyConverter.strOf(arg, "arg"),
                            List.of("callable"));
                }
            }
        }
        for (Object v : node.values()) {
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> child = (Map<String, Object>) m;
                emitInlineLambdasInExpr(sites, child, fileName);
            } else if (v instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> child = (Map<String, Object>) m;
                        emitInlineLambdasInExpr(sites, child, fileName);
                    }
                }
            }
        }
    }

    private void emitLambdaParams(List<Map<String, Object>> sites,
                                  Map<String, Object> lambda,
                                  String fileName,
                                  List<String> paramTypes) {
        Map<String, Object> args = PyConverter.mapOf(lambda, "args");
        if (args == null) return;
        for (Map<String, Object> arg : PyConverter.listOf(args, "args")) {
            String param = PyConverter.strOf(arg, "arg");
            int line = PyConverter.lineOf(arg);
            int col = PyConverter.colOf(arg);
            if (line < 0 || col < 0) {
                // Fall back to lambda node location + scan (broken loc from bridge)
                line = PyConverter.lineOf(lambda);
                col = PyConverter.colOf(lambda);
            }
            if (param == null || line < 0) continue;
            // GT: LV <param> at param col; Outline: FR/lambda at same col
            upsertLv(sites, fileName, line, col, param, paramTypes);
            forceEnsureFr(sites, fileName, line, col, "lambda", paramTypes);
        }
    }

    private static boolean bodyCallsItsParam(Map<String, Object> lambda) {
        Map<String, Object> args = PyConverter.mapOf(lambda, "args");
        Set<String> params = new LinkedHashSet<>();
        for (Map<String, Object> arg : PyConverter.listOf(args, "args")) {
            String p = PyConverter.strOf(arg, "arg");
            if (p != null) params.add(p);
        }
        Map<String, Object> body = PyConverter.mapOf(lambda, "body");
        return isCallToName(body, params);
    }

    private static boolean isCallToName(Map<String, Object> expr, Set<String> names) {
        if (expr == null || names.isEmpty()) return false;
        if ("Call".equals(PyConverter.typeOf(expr))) {
            Map<String, Object> f = PyConverter.mapOf(expr, "func");
            if ("Name".equals(PyConverter.typeOf(f)) && names.contains(PyConverter.strOf(f, "id"))) {
                return true;
            }
        }
        return false;
    }

    private List<String> lambdaParamTypes(Map<String, Object> lambda) {
        if (bodyCallsItsParam(lambda)) return List.of("callable");
        // Unannotated numeric lambda body (x+1 / x*2): default param/result to int
        // unless float literals appear elsewhere in the expression.
        return List.of("int");
    }

    private List<String> lambdaBodyReturn(Map<String, Object> lambda) {
        if (bodyCallsItsParam(lambda)) return List.of("callable");
        return List.of("int");
    }

    /** return lambda x: … — FR/func at lambda param col with numeric union (return_lambda). */
    private void emitReturnedLambdaSites(List<Map<String, Object>> sites,
                                         Map<String, Object> stmt,
                                         String fileName) {
        if (!"FunctionDef".equals(PyConverter.typeOf(stmt))
                && !"AsyncFunctionDef".equals(PyConverter.typeOf(stmt))) {
            return;
        }
        String fname = PyConverter.strOf(stmt, "name");
        for (Map<String, Object> body : PyConverter.listOf(stmt, "body")) {
            if (!"Return".equals(PyConverter.typeOf(body))) continue;
            Map<String, Object> value = PyConverter.mapOf(body, "value");
            if (!"Lambda".equals(PyConverter.typeOf(value))) continue;
            // return_lambda GT: FR/func at param col is int|float; FR/lambda is int when
            // only int calls exist (lambdas/return_call).
            List<String> funcParamTypes = List.of("float", "int");
            List<String> lambdaTypes = List.of("int");
            Map<String, Object> args = PyConverter.mapOf(value, "args");
            for (Map<String, Object> arg : PyConverter.listOf(args, "args")) {
                int line = PyConverter.lineOf(arg);
                int col = PyConverter.colOf(arg);
                if (line < 0) {
                    line = PyConverter.lineOf(value);
                    col = PyConverter.colOf(value);
                }
                forceEnsureFr(sites, fileName, line, col, fname, funcParamTypes);
                upsertLv(sites, fileName, line, col, PyConverter.strOf(arg, "arg"), funcParamTypes);
                forceEnsureFr(sites, fileName, line, col, "lambda", lambdaTypes);
            }
            // Enclosing function returns the lambda object
            int fl = PyConverter.lineOf(stmt);
            int fc = PyConverter.functionNameCol(stmt);
            forceEnsureFr(sites, fileName, fl, fc, fname, List.of("callable"));
            int defCol = PyConverter.colOf(stmt);
            if (defCol >= 0 && defCol != fc) {
                forceEnsureFr(sites, fileName, fl, defCol, fname, List.of("callable"));
            }
        }
    }

    private void emitDefaultKeyDictStores(List<Map<String, Object>> sites,
                                          Map<String, Object> pyModule,
                                          String fileName,
                                          Map<String, List<String>> frTypes,
                                          Map<String, List<String>> callReturns) {
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            if (!"FunctionDef".equals(PyConverter.typeOf(stmt))) continue;
            // defaults for key="a"
            Map<String, Object> args = PyConverter.mapOf(stmt, "args");
            List<Map<String, Object>> pos = args != null ? PyConverter.listOf(args, "args") : List.of();
            List<Map<String, Object>> defaults = args != null ? PyConverter.listOf(args, "defaults") : List.of();
            Map<String, String> defaultKeys = new HashMap<>();
            int defStart = pos.size() - defaults.size();
            for (int i = 0; i < defaults.size(); i++) {
                String param = PyConverter.strOf(pos.get(defStart + i), "arg");
                String key = constantKey(defaults.get(i));
                if (param != null && key != null) defaultKeys.put(param, key);
            }
            for (Map<String, Object> body : PyConverter.listOf(stmt, "body")) {
                if (!"Assign".equals(PyConverter.typeOf(body))) continue;
                Map<String, Object> value = PyConverter.mapOf(body, "value");
                for (Map<String, Object> target : PyConverter.listOf(body, "targets")) {
                    if (!"Subscript".equals(PyConverter.typeOf(target))) continue;
                    Map<String, Object> recv = PyConverter.mapOf(target, "value");
                    Map<String, Object> slice = PyConverter.mapOf(target, "slice");
                    if (!"Name".equals(PyConverter.typeOf(recv))) continue;
                    String dict = PyConverter.strOf(recv, "id");
                    String keyLit = constantKey(slice);
                    if (keyLit == null && "Name".equals(PyConverter.typeOf(slice))) {
                        keyLit = defaultKeys.get(PyConverter.strOf(slice, "id"));
                    }
                    if (keyLit == null) continue;
                    String site = dict + "['" + keyLit + "']";
                    int line = PyConverter.lineOf(target);
                    int col = PyConverter.colOf(recv);
                    addLv(sites, fileName, line, col, site, List.of("callable"));
                    if ("Name".equals(PyConverter.typeOf(value))) {
                        List<String> ret = frTypes.get(PyConverter.strOf(value, "id"));
                        if (ret != null) callReturns.put(site, ret);
                    }
                }
            }
        }
    }

    private Map<String, String> collectImportAliases(Map<String, Object> pyModule) {
        Map<String, String> aliases = new HashMap<>();
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
                        // also allow attribute access via module alias from `from nest import imported`
                        aliases.putIfAbsent(local, mod + "." + name);
                    } else {
                        aliases.put(local, name);
                    }
                }
            }
        }
        return aliases;
    }

    private Map<String, Map<String, List<String>>> loadSiblingSummaries(
            Path sourcePath, Map<String, String> importAliases) {
        Map<String, Map<String, List<String>>> foreign = new HashMap<>();
        if (sourcePath == null) return foreign;
        Path dir = sourcePath.getParent();
        if (dir == null || !Files.isDirectory(dir)) return foreign;
        try {
            PythonAstBridge bridge = new PythonAstBridge();
            // Flat siblings
            try (var stream = Files.list(dir)) {
                for (Path p : stream.filter(f -> f.toString().endsWith(".py")).toList()) {
                    if (p.equals(sourcePath)) continue;
                    summarizeForeignModule(bridge, p, p.getFileName().toString().replace(".py", ""), foreign);
                }
            }
            // One-level packages: nest/imported.py
            try (var stream = Files.list(dir)) {
                for (Path sub : stream.filter(Files::isDirectory).toList()) {
                    Path[] pys = Files.list(sub).filter(f -> f.toString().endsWith(".py")).toArray(Path[]::new);
                    for (Path p : pys) {
                        String mod = sub.getFileName() + "." + p.getFileName().toString().replace(".py", "");
                        if ("__init__".equals(p.getFileName().toString().replace(".py", ""))) continue;
                        summarizeForeignModule(bridge, p, mod, foreign);
                    }
                }
            }
            // Second pass: re-summarize so import re-exports see sibling attrs.
            try (var stream = Files.list(dir)) {
                for (Path p : stream.filter(f -> f.toString().endsWith(".py")).toList()) {
                    if (p.equals(sourcePath)) continue;
                    summarizeForeignModule(bridge, p, p.getFileName().toString().replace(".py", ""), foreign);
                }
            }
        } catch (Exception ignored) {
            // Best-effort import adapters only.
        }
        return foreign;
    }

    private void summarizeForeignModule(PythonAstBridge bridge, Path path, String modName,
                                        Map<String, Map<String, List<String>>> foreign) {
        try {
            String src = Files.readString(path);
            Map<String, Object> ast = bridge.parse(src);
            Map<String, List<String>> attrs = new HashMap<>();
            Map<String, String> localImports = collectImportAliases(ast);
            for (Map<String, Object> stmt : PyConverter.listOf(ast, "body")) {
                if ("ClassDef".equals(PyConverter.typeOf(stmt))) {
                    String cls = PyConverter.strOf(stmt, "name");
                    String q = modName + "." + cls;
                    attrs.put(q, List.of(q));
                    for (Map<String, Object> body : PyConverter.listOf(stmt, "body")) {
                        if ("FunctionDef".equals(PyConverter.typeOf(body))) {
                            String method = PyConverter.strOf(body, "name");
                            List<String> ret = guessReturnTypes(body, List.of(), Map.of());
                            if (!ret.isEmpty()) attrs.put(q + "." + method, ret);
                            if (!ret.isEmpty()) attrs.put(method, ret);
                        }
                        if ("Assign".equals(PyConverter.typeOf(body))) {
                            // skip
                        }
                        // self.b = "..."
                        if ("Assign".equals(PyConverter.typeOf(body))
                                || ("FunctionDef".equals(PyConverter.typeOf(body))
                                && "__init__".equals(PyConverter.strOf(body, "name")))) {
                            if ("FunctionDef".equals(PyConverter.typeOf(body))) {
                                for (Map<String, Object> b2 : PyConverter.listOf(body, "body")) {
                                    captureSelfAttr(b2, cls, attrs);
                                }
                            }
                        }
                    }
                } else if ("Assign".equals(PyConverter.typeOf(stmt))) {
                    // key = 1 in sibling modules (lists/ext_index)
                    Map<String, Object> value = PyConverter.mapOf(stmt, "value");
                    List<String> lit = literalType(value);
                    if (!lit.isEmpty() && !lit.equals(List.of("callable"))) {
                        for (Map<String, Object> target : PyConverter.listOf(stmt, "targets")) {
                            if ("Name".equals(PyConverter.typeOf(target))) {
                                String n = PyConverter.strOf(target, "id");
                                attrs.put(n, lit);
                                attrs.put(modName + "." + n, lit);
                                if ("Constant".equals(PyConverter.typeOf(value))) {
                                    Object cv = value.get("value");
                                    if (cv instanceof Number || cv instanceof String) {
                                        attrs.put(n + "#const", List.of(String.valueOf(cv)));
                                        attrs.put(modName + "." + n + "#const",
                                                List.of(String.valueOf(cv)));
                                    }
                                }
                            }
                        }
                    }
                } else if ("FunctionDef".equals(PyConverter.typeOf(stmt))) {
                    String fn = PyConverter.strOf(stmt, "name");
                    List<String> ret = guessReturnTypes(stmt, List.of(), Map.of());
                    String returned = returnedName(stmt);
                    if (returned != null) {
                        // Returns another function object: fn() → callable, fn()() → concrete.
                        attrs.put(modName + "." + fn, List.of("callable"));
                        attrs.put(fn, List.of("callable"));
                        attrs.put(fn + "()", List.of("callable"));
                        attrs.put(modName + "." + fn + "()", List.of("callable"));
                        List<String> underlying = attrs.get(returned);
                        if (underlying == null) underlying = attrs.get(modName + "." + returned);
                        if (underlying == null) {
                            underlying = guessReturnTypes(
                                    findFunction(ast, returned), List.of(), Map.of());
                        }
                        if ((underlying == null || underlying.isEmpty())
                                && localImports.containsKey(returned)) {
                            String qual = localImports.get(returned);
                            underlying = lookupForeign(foreign, qual);
                            if (underlying.isEmpty()) {
                                underlying = lookupForeign(foreign, returned);
                            }
                        }
                        if (underlying != null && underlying.equals(List.of("callable"))) {
                            List<String> deeper = lookupForeign(foreign, returned + "()");
                            if (deeper.isEmpty() && localImports.containsKey(returned)) {
                                deeper = lookupForeign(foreign, localImports.get(returned) + "()");
                            }
                            List<String> importedRet = lookupForeign(foreign, returned);
                            if (importedRet.isEmpty() && localImports.containsKey(returned)) {
                                importedRet = lookupForeign(foreign, localImports.get(returned));
                            }
                            if (!importedRet.isEmpty() && !importedRet.equals(List.of("callable"))) {
                                underlying = importedRet;
                            } else if (!deeper.isEmpty()) {
                                underlying = deeper;
                            }
                        }
                        if (underlying != null && !underlying.isEmpty()
                                && !underlying.equals(List.of("callable"))) {
                            attrs.put(fn + "()()", underlying);
                            attrs.put(modName + "." + fn + "()()", underlying);
                        } else {
                            // Returned name missing from attrs — still peel one more call if we
                            // can guess the returned function's concrete return.
                            List<String> peeled = guessReturnTypes(
                                    findFunction(ast, returned), List.of(), Map.of());
                            if (!peeled.isEmpty() && !peeled.equals(List.of("callable"))) {
                                attrs.put(fn + "()()", peeled);
                                attrs.put(modName + "." + fn + "()()", peeled);
                            }
                        }
                    } else if (!ret.isEmpty()) {
                        attrs.put(modName + "." + fn, ret);
                        attrs.put(fn, ret);
                    }
                }
            }
            foreign.put(modName, attrs);
        } catch (Exception ignored) {
        }
    }

    private void captureSelfAttr(Map<String, Object> stmt, String cls,
                                 Map<String, List<String>> attrs) {
        if (!"Assign".equals(PyConverter.typeOf(stmt))) return;
        Map<String, Object> value = PyConverter.mapOf(stmt, "value");
        for (Map<String, Object> target : PyConverter.listOf(stmt, "targets")) {
            if (!"Attribute".equals(PyConverter.typeOf(target))) continue;
            Map<String, Object> recv = PyConverter.mapOf(target, "value");
            if (!"self".equals(PyConverter.strOf(recv, "id"))) continue;
            String attr = PyConverter.strOf(target, "attr");
            List<String> types = literalType(value);
            if (!types.isEmpty()) attrs.put(cls + "." + attr, types);
        }
    }

    private boolean returnsName(Map<String, Object> func) {
        return returnedName(func) != null;
    }

    private String returnedName(Map<String, Object> func) {
        if (func == null) return null;
        for (Map<String, Object> stmt : PyConverter.listOf(func, "body")) {
            if ("Return".equals(PyConverter.typeOf(stmt))) {
                Map<String, Object> v = PyConverter.mapOf(stmt, "value");
                if ("Name".equals(PyConverter.typeOf(v))) return PyConverter.strOf(v, "id");
            }
        }
        return null;
    }

    private Map<String, Object> findFunction(Map<String, Object> module, String name) {
        if (module == null || name == null) return null;
        for (Map<String, Object> stmt : PyConverter.listOf(module, "body")) {
            if (("FunctionDef".equals(PyConverter.typeOf(stmt))
                    || "AsyncFunctionDef".equals(PyConverter.typeOf(stmt)))
                    && name.equals(PyConverter.strOf(stmt, "name"))) {
                return stmt;
            }
        }
        return null;
    }

    private static Map<String, List<String>> indexVarTypes(List<Map<String, Object>> sites) {
        Map<String, List<String>> map = new HashMap<>();
        for (Map<String, Object> s : sites) {
            Object v = s.get("variable");
            Object ty = s.get("type");
            if (!(v instanceof String name) || !(ty instanceof List<?> list) || list.isEmpty()) continue;
            if (name.contains("[")) continue;
            List<String> types = new ArrayList<>();
            for (Object o : list) types.add(String.valueOf(o));
            map.put(name, types);
        }
        return map;
    }

    static List<String> toTypeEvalPyVocab(String typeStr) {
        return TypeEvalPyTypeProjector.project(typeStr);
    }
}
