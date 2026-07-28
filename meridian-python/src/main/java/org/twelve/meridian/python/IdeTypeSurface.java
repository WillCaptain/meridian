package org.twelve.meridian.python;

import org.twelve.gcp.ast.AST;
import org.twelve.gcp.node.expression.Assignment;
import org.twelve.gcp.node.expression.Variable;
import org.twelve.gcp.node.function.Argument;
import org.twelve.gcp.node.function.FunctionNode;
import org.twelve.gcp.node.statement.VariableDeclarator;
import org.twelve.gcp.node.expression.typeable.TypeNode;
import org.twelve.gcp.outline.Outline;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IDE / hover surface: full inferred types including {@code Union} / {@code Optional}.
 *
 * <p>Separate from the compile surface ({@link AnnotationPolicy},
 * {@link FunctionSpecializer}, {@link CompileSourcePruner}). LSP (parked) should
 * call this API; mypyc must not.
 *
 * <p>Keys match annotation-writer conventions: {@code func#param},
 * {@code func#return}, bare variable names.
 */
public final class IdeTypeSurface {

    private final TypeAnnotationGenerator typeGen = new TypeAnnotationGenerator();
    private final PythonAnnotationWriter writer = new PythonAnnotationWriter();
    private final PythonInferencer inferencer = new PythonInferencer();

    /**
     * Hover map for a library buffer, optionally refined by usage call sites.
     * Never applies {@link AnnotationPolicy} filtering.
     */
    public Map<String, String> hoverTypes(String librarySource, String usageSource) {
        if (librarySource == null || librarySource.isBlank()) return Map.of();
        if (usageSource != null && !usageSource.isBlank()) {
            PythonInferencer.ContextInferResult ctx =
                    inferencer.inferWithContextDetailed(librarySource, usageSource);
            return collect(ctx.libraryAst(), ctx.usageAst());
        }
        PythonInferenceResult inferred = inferencer.inferDetailed(librarySource);
        return collect(inferred.gcpAst(), null);
    }

    /** Collect from already-inferred ASTs (no re-infer). */
    public Map<String, String> collect(AST libraryAst, AST usageAst) {
        Map<String, String> out = new LinkedHashMap<>();
        if (libraryAst == null) return out;
        for (var stmt : libraryAst.program().body().statements()) {
            if (!(stmt instanceof VariableDeclarator vd)) continue;
            for (Assignment a : vd.assignments()) {
                if (a.lhs() == null) continue;
                String rawName = a.lhs().lexeme().trim().replaceAll(":.*", "").trim();
                if (rawName.isBlank()) continue;
                TypeNode declared = (a.lhs() instanceof Variable v) ? v.declared() : null;
                if (declared != null && typeGen.typeNodeToStr(declared) != null) continue;

                if (a.rhs() instanceof FunctionNode fn) {
                    collectFunction(rawName, fn, out);
                } else {
                    Outline inferred = a.rhs() != null ? a.rhs().outline() : null;
                    String typeStr = typeGen.outlineToTypeStr(inferred);
                    if (typeStr != null) out.put(rawName, typeStr);
                }
            }
        }
        if (usageAst != null) {
            // Call-site facts may refine params; keep existing wide types when
            // already present (IDE prefers definition-width over first-wins).
            Map<String, String> fromCalls = writer.collectCallSiteHints(libraryAst, usageAst);
            for (Map.Entry<String, String> e : fromCalls.entrySet()) {
                out.putIfAbsent(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    private void collectFunction(String funcName, FunctionNode fn, Map<String, String> out) {
        for (Argument arg : typeGen.flattenFunctionArgs(fn)) {
            TypeNode declared = arg.declared();
            if (declared != null) {
                String s = typeGen.typeNodeToStr(declared);
                if (s != null) out.put(funcName + "#" + arg.name(), s);
                continue;
            }
            // IDE: full width (Union / Optional / Number tower), not compile-narrowed.
            String typeStr = typeGen.outlineToTypeStr(arg.outline());
            if (typeStr != null) {
                out.put(funcName + "#" + arg.name(), typeStr);
            }
        }
        String ret = typeGen.functionReturnType(fn);
        if (ret != null) {
            out.put(funcName + "#return", ret);
        }
    }
}
