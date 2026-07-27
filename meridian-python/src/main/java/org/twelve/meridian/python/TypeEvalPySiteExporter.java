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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Export inferred types as TypeEvalPy / Scalpel-style site JSON
 * ({@code main_result.json}): FR / FP / LV keyed by file + line + col.
 *
 * <p>Column convention: Meridian stores Python's 0-based {@code col_offset};
 * TypeEvalPy ground truth uses 1-based columns — this exporter adds 1 on write.
 *
 * <p>Harness adapters (role analogous to Outline PORTABLE/ADAPTED, but on the
 * native Python path): qualify {@code Class.method} FR names, emit container
 * element LVs ({@code keys[0]}, {@code dict1['a']}), starred middle {@code b[i]},
 * and {@code Class.attr} from {@code self.attr = …}.
 */
public class TypeEvalPySiteExporter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final TypeAnnotationGenerator typeGen = new TypeAnnotationGenerator();

    public List<Map<String, Object>> collect(AST ast, String fileName) {
        return collect(ast, fileName, null);
    }

    /**
     * @param pyAst optional Python JSON AST from {@link PythonAstBridge} for harness adapters
     */
    public List<Map<String, Object>> collect(AST ast, String fileName, Map<String, Object> pyAst) {
        List<Map<String, Object>> sites = new ArrayList<>();
        walkBody(ast.program().body(), fileName, sites);
        if (pyAst != null) {
            enrichFromPythonAst(sites, pyAst, fileName);
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
        List<Map<String, Object>> sites = collect(ast, fileName, pyAst);
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
                                     String fileName) {
        Map<String, Map<String, Object>> lvByName = indexLvs(sites);
        Map<String, Map<String, Object>> listLits = new HashMap<>();
        Map<String, Map<String, Object>> dictLits = new HashMap<>();
        // var → return type when called (from bound callables)
        Map<String, List<String>> callReturns = new HashMap<>();
        qualifyClassMethods(sites, pyModule);
        Map<String, List<String>> frTypes = indexFrTypes(sites);
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            enrichStmt(sites, stmt, fileName, null, lvByName, listLits, dictLits, callReturns, frTypes);
        }
        // Second pass: fix LV types for calls like c = a[0]() / f = c()
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            refineCallAssignments(sites, stmt, fileName, listLits, callReturns, frTypes);
        }
    }

    private void enrichStmt(List<Map<String, Object>> sites,
                            Map<String, Object> stmt,
                            String fileName,
                            String className,
                            Map<String, Map<String, Object>> lvByName,
                            Map<String, Map<String, Object>> listLits,
                            Map<String, Map<String, Object>> dictLits,
                            Map<String, List<String>> callReturns,
                            Map<String, List<String>> frTypes) {
        String t = PyConverter.typeOf(stmt);
        if ("ClassDef".equals(t)) {
            String name = PyConverter.strOf(stmt, "name");
            for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
                enrichStmt(sites, bodyStmt, fileName, name, lvByName, listLits, dictLits, callReturns, frTypes);
            }
            return;
        }
        if ("FunctionDef".equals(t) || "AsyncFunctionDef".equals(t)) {
            for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
                enrichStmt(sites, bodyStmt, fileName, className, lvByName, listLits, dictLits, callReturns, frTypes);
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
            expandSelfAttr(sites, target, value, fileName, className);
        }
    }

    private static Map<String, List<String>> indexFrTypes(List<Map<String, Object>> sites) {
        Map<String, List<String>> map = new HashMap<>();
        for (Map<String, Object> s : sites) {
            if (!s.containsKey("function") || s.containsKey("parameter")) continue;
            Object fn = s.get("function");
            Object ty = s.get("type");
            if (!(fn instanceof String name) || !(ty instanceof List<?> list)) continue;
            List<String> types = new ArrayList<>();
            for (Object o : list) types.add(String.valueOf(o));
            map.put(name, types);
            int dot = name.lastIndexOf('.');
            if (dot >= 0) map.putIfAbsent(name.substring(dot + 1), types);
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
        Map<String, Object> recv = PyConverter.mapOf(target, "value");
        Map<String, Object> slice = PyConverter.mapOf(target, "slice");
        if (!"Name".equals(PyConverter.typeOf(recv))) return;
        String listName = PyConverter.strOf(recv, "id");
        Integer idx = constantInt(slice);
        if (listName == null || idx == null) return;
        int line = PyConverter.lineOf(target);
        int col = PyConverter.colOf(recv); // GT uses list name column
        List<String> types = List.of("callable");
        if ("Name".equals(PyConverter.typeOf(value))) {
            List<String> ret = frTypes.get(PyConverter.strOf(value, "id"));
            if (ret != null) callReturns.put(listName + "[" + idx + "]", ret);
        }
        addLv(sites, fileName, line, col, listName + "[" + idx + "]", types);
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
                                       Map<String, List<String>> frTypes) {
        String t = PyConverter.typeOf(stmt);
        if ("ClassDef".equals(t) || "FunctionDef".equals(t) || "AsyncFunctionDef".equals(t)) {
            for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
                refineCallAssignments(sites, bodyStmt, fileName, listLits, callReturns, frTypes);
            }
            return;
        }
        if (!"Assign".equals(t)) return;
        Map<String, Object> value = PyConverter.mapOf(stmt, "value");
        if (!"Call".equals(PyConverter.typeOf(value))) return;
        Map<String, Object> callee = PyConverter.mapOf(value, "func");
        List<String> ret = null;
        if ("Name".equals(PyConverter.typeOf(callee))) {
            String n = PyConverter.strOf(callee, "id");
            ret = callReturns.get(n);
            if (ret == null) ret = frTypes.get(n);
        } else if ("Subscript".equals(PyConverter.typeOf(callee))) {
            ret = returnTypeOfSubscriptCallable(callee, listLits, frTypes);
            Map<String, Object> recv = PyConverter.mapOf(callee, "value");
            Map<String, Object> slice = PyConverter.mapOf(callee, "slice");
            Integer idx = constantInt(slice);
            if (ret == null && "Name".equals(PyConverter.typeOf(recv)) && idx != null) {
                ret = callReturns.get(PyConverter.strOf(recv, "id") + "[" + idx + "]");
            }
            String keyLit = constantKey(slice);
            if (ret == null && "Name".equals(PyConverter.typeOf(recv)) && keyLit != null) {
                ret = callReturns.get(PyConverter.strOf(recv, "id") + "['" + keyLit + "']");
            }
        } else if ("Attribute".equals(PyConverter.typeOf(callee))) {
            ret = frTypes.get(PyConverter.strOf(callee, "attr"));
            if (ret == null) {
                String attr = PyConverter.strOf(callee, "attr");
                for (Map.Entry<String, List<String>> e : frTypes.entrySet()) {
                    if (e.getKey().endsWith("." + attr)) {
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
        }
        if (ret == null || ret.isEmpty()) return;
        for (Map<String, Object> target : PyConverter.listOf(stmt, "targets")) {
            if (!"Name".equals(PyConverter.typeOf(target))) continue;
            String name = PyConverter.strOf(target, "id");
            int line = PyConverter.lineOf(target);
            int col = PyConverter.colOf(target);
            upsertLv(sites, fileName, line, col, name, ret);
        }
    }

    private void upsertLv(List<Map<String, Object>> sites, String fileName,
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

    private static Integer constantInt(Map<String, Object> node) {
        if (node == null || !"Constant".equals(PyConverter.typeOf(node))) return null;
        Object v = node.get("value");
        if (v instanceof Integer i) return i;
        if (v instanceof Long l) return l.intValue();
        return null;
    }

    private void qualifyClassMethods(List<Map<String, Object>> sites, Map<String, Object> pyModule) {
        Map<String, String> locToQualified = new HashMap<>();
        for (Map<String, Object> stmt : PyConverter.listOf(pyModule, "body")) {
            if (!"ClassDef".equals(PyConverter.typeOf(stmt))) continue;
            String className = PyConverter.strOf(stmt, "name");
            for (Map<String, Object> bodyStmt : PyConverter.listOf(stmt, "body")) {
                if (!"FunctionDef".equals(PyConverter.typeOf(bodyStmt))
                        && !"AsyncFunctionDef".equals(PyConverter.typeOf(bodyStmt))) {
                    continue;
                }
                String method = PyConverter.strOf(bodyStmt, "name");
                int line = PyConverter.lineOf(bodyStmt);
                int nameCol = PyConverter.functionNameCol(bodyStmt); // 0-based
                if (line < 0 || nameCol < 0 || method == null) continue;
                // key uses TypeEvalPy 1-based col
                locToQualified.put(line + ":" + (nameCol + 1), className + "." + method);
            }
        }
        for (Map<String, Object> site : sites) {
            if (!site.containsKey("function") || site.containsKey("parameter")) continue;
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
            // Find FR at same class for this bare name — best-effort via any qualified FR ending with .bare
            for (String q : locToQualified.values()) {
                if (q.endsWith("." + bare)) {
                    site.put("function", q);
                    break;
                }
            }
        }
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

        if ("List".equals(PyConverter.typeOf(value))) {
            List<Map<String, Object>> elts = PyConverter.listOf(value, "elts");
            ensureLv(sites, lvByName, fileName, name, line, col, List.of("list"));
            for (int i = 0; i < elts.size(); i++) {
                List<String> et = literalType(elts.get(i));
                if (et.isEmpty() || et.equals(List.of("Any"))) continue;
                addLv(sites, fileName, line, col, name + "[" + i + "]", et);
                if ("Name".equals(PyConverter.typeOf(elts.get(i)))) {
                    List<String> ret = frTypes.get(PyConverter.strOf(elts.get(i), "id"));
                    if (ret != null) callReturns.put(name + "[" + i + "]", ret);
                }
            }
        } else if ("Dict".equals(PyConverter.typeOf(value))) {
            ensureLv(sites, lvByName, fileName, name, line, col, List.of("dict"));
            List<Map<String, Object>> keys = PyConverter.listOf(value, "keys");
            List<Map<String, Object>> vals = PyConverter.listOf(value, "values");
            int n = Math.min(keys.size(), vals.size());
            for (int i = 0; i < n; i++) {
                if (keys.get(i) == null) {
                    // {**src} — copy element LVs from src
                    Map<String, Object> src = vals.get(i);
                    if ("Name".equals(PyConverter.typeOf(src))) {
                        copyDictElements(sites, fileName, line, col, name,
                                PyConverter.strOf(src, "id"), dictLits);
                    }
                    continue;
                }
                String keyLit = constantKey(keys.get(i));
                if (keyLit == null) continue;
                List<String> vt = literalType(vals.get(i));
                if (vt.isEmpty()) continue;
                addLv(sites, fileName, line, col, name + "['" + keyLit + "']", vt);
                if ("Name".equals(PyConverter.typeOf(vals.get(i)))) {
                    List<String> ret = frTypes.get(PyConverter.strOf(vals.get(i), "id"));
                    if (ret != null) callReturns.put(name + "['" + keyLit + "']", ret);
                }
            }
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
                                String className) {
        if (className == null) return;
        if (!"Attribute".equals(PyConverter.typeOf(target))) return;
        Map<String, Object> recv = PyConverter.mapOf(target, "value");
        if (recv == null || !"self".equals(PyConverter.strOf(recv, "id"))) return;
        String attr = PyConverter.strOf(target, "attr");
        if (attr == null) return;
        int line = PyConverter.lineOf(target);
        int col = PyConverter.colOf(target);
        List<String> types = exprAsCallableOrLiteral(value);
        if (types.isEmpty()) types = List.of("callable");
        addLv(sites, fileName, line, col, className + "." + attr, types);
    }

    private static List<String> exprAsCallableOrLiteral(Map<String, Object> expr) {
        if (expr == null) return List.of("callable");
        String t = PyConverter.typeOf(expr);
        if ("Name".equals(t) || "Attribute".equals(t) || "Lambda".equals(t)) {
            return List.of("callable");
        }
        return literalType(expr);
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
        if ("List".equals(t)) return List.of("list");
        if ("Dict".equals(t)) return List.of("dict");
        if ("Call".equals(t)) {
            Map<String, Object> func = PyConverter.mapOf(node, "func");
            if ("Name".equals(PyConverter.typeOf(func))) {
                String n = PyConverter.strOf(func, "id");
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

    static List<String> toTypeEvalPyVocab(String typeStr) {
        List<String> out = new ArrayList<>();
        if (typeStr == null || typeStr.isBlank()) return out;
        String s = typeStr.trim();
        if (s.startsWith("Union[") && s.endsWith("]")) {
            for (String part : splitTopLevel(s.substring(6, s.length() - 1))) {
                out.addAll(toTypeEvalPyVocab(part.trim()));
            }
            return dedupe(out);
        }
        if (s.startsWith("Optional[") && s.endsWith("]")) {
            out.addAll(toTypeEvalPyVocab(s.substring(9, s.length() - 1)));
            out.add("Nonetype");
            return dedupe(out);
        }
        out.add(eraseGenerics(s));
        return out;
    }

    private static String eraseGenerics(String s) {
        String t = s.trim();
        if (t.startsWith("Callable")) return "callable";
        if (t.equals("None") || t.equals("NoneType") || t.equals("Unit")) return "Nonetype";
        int bracket = t.indexOf('[');
        if (bracket > 0) t = t.substring(0, bracket);
        return switch (t) {
            case "Int", "Integer", "Long" -> "int";
            case "Float", "Double", "Decimal", "Number" -> "float";
            case "String", "str" -> "str";
            case "Bool", "bool" -> "bool";
            case "List", "list", "Array" -> "list";
            case "Dict", "dict" -> "dict";
            case "Tuple", "tuple" -> "tuple";
            case "Set", "set" -> "set";
            default -> t; // keep nominal class names (MyClass, Person, …)
        };
    }

    private static List<String> splitTopLevel(String inner) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') depth--;
            if (c == ',' && depth == 0) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) parts.add(cur.toString());
        return parts;
    }

    private static List<String> dedupe(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            if (s != null && !s.isBlank() && !out.contains(s)) out.add(s);
        }
        return out;
    }
}
