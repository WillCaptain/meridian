package org.twelve.meridian.python;

import org.twelve.gcp.ast.ASF;
import org.twelve.gcp.ast.AST;
import org.twelve.meridian.python.converter.ModuleLoader;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Primary entry point for the meridian-python pipeline:
 *
 * <pre>
 *   Python source
 *       ↓  PythonAstBridge  (py_ast_dump.py subprocess)
 *   Python JSON AST
 *       ↓  PythonGCPConverter
 *   GCP AST
 *       ↓  OutlineInferencer  (via ASF.infer())
 *   Typed GCP AST
 *       ↓  TypeAnnotationGenerator
 *   .pyi stub
 * </pre>
 */
public class PythonInferencer {

    private final ASF asf;
    private final PythonAstBridge bridge;
    private final PythonGCPConverter converter;
    private final TypeAnnotationGenerator generator;

    /** In-memory module registry for P6 cross-file inference. */
    private final Map<String, String> moduleRegistry = new HashMap<>();

    public PythonInferencer() {
        this.asf = new ASF();
        this.bridge = new PythonAstBridge();
        this.converter = new PythonGCPConverter(asf);
        this.generator = new TypeAnnotationGenerator();
    }

    // ── P6: module registry ────────────────────────────────────────────────────

    /**
     * Register an in-memory Python module source so it can be auto-loaded when
     * another module imports from it.
     *
     * <p>Example:
     * <pre>
     *   inferencer.registerModule("utils", "def add(a, b): return a + b");
     *   AST ast = inferencer.infer("from utils import add\nresult = add(1, 2)");
     * </pre>
     *
     * @param moduleName dotted module name (e.g. {@code "utils"}, {@code "pkg.math"})
     * @param source     Python source text of the module
     */
    public void registerModule(String moduleName, String source) {
        moduleRegistry.put(moduleName, source);
    }

    /**
     * Build a {@link ModuleLoader} that first checks the in-memory registry, then
     * falls back to looking for a {@code .py} file relative to {@code baseDir}.
     *
     * @param baseDir optional directory to search for {@code moduleName.py} files;
     *                {@code null} means in-memory only
     */
    private ModuleLoader buildLoader(File baseDir) {
        Set<String> loaded = new HashSet<>();
        return moduleName -> {
            if (!loaded.add(moduleName)) return null;  // cycle / already-loaded guard
            // 1. In-memory registry (highest priority)
            String src = moduleRegistry.get(moduleName);
            if (src != null) {
                Map<String, Object> pyAst = bridge.parse(src);
                return converter.convert(pyAst);
            }
            // 2. File-system fallback (when a base directory is provided)
            if (baseDir != null) {
                File modFile = new File(baseDir,
                        moduleName.replace('.', File.separatorChar) + ".py");
                if (modFile.exists()) {
                    Map<String, Object> pyAst = bridge.parseFile(modFile);
                    return converter.convert(pyAst);
                }
            }
            return null;
        };
    }

    // ── infer from string ──────────────────────────────────────────────────────

    /**
     * Parse and infer types for the given Python source code.
     *
     * <p>Runs the same GCP + {@link PythonSemanticRefiner} pipeline as
     * {@link #inferDetailed(String)}; returns only the GCP AST for callers that
     * do not need shared refine facts.
     *
     * @return the typed GCP AST (inference already applied)
     */
    public AST infer(String pythonSource) {
        return inferDetailed(pythonSource).gcpAst();
    }

    /**
     * Parse and infer types for a Python source file.
     *
     * <p>Sibling {@code .py} files in the same directory are automatically resolved
     * when imported; the in-memory registry (if populated) takes priority.
     */
    public AST inferFile(File pythonFile) throws IOException {
        return inferFileDetailed(pythonFile).ast();
    }

    /**
     * Like {@link #inferFile(File)} but also returns the Python JSON AST so site
     * export can apply TypeEvalPy harness adapters (qualified methods, container LVs).
     */
    public InferResult inferFileDetailed(File pythonFile) throws IOException {
        converter.setModuleLoader(buildLoader(pythonFile.getParentFile()));
        Map<String, Object> pyAst = bridge.parseFile(pythonFile);
        AST ast = converter.convert(pyAst);
        asf.infer();
        PythonInferenceResult inference =
                new PythonSemanticRefiner().refine(
                        ast, pyAst, pythonFile.getName(), pythonFile.toPath(), moduleRegistry);
        return new InferResult(ast, pyAst, inference);
    }

    /**
     * Parse + infer + refine a Python source string (no file path).
     * Returns the shared {@link PythonInferenceResult} used by annotate / sites.
     */
    public PythonInferenceResult inferDetailed(String pythonSource) {
        converter.setModuleLoader(moduleRegistry.isEmpty() ? null : buildLoader(null));
        Map<String, Object> pyAst = bridge.parse(pythonSource);
        AST ast = converter.convert(pyAst);
        asf.infer();
        return new PythonSemanticRefiner().refine(ast, pyAst, "<string>", null, moduleRegistry);
    }

    /** Result of {@link #inferFileDetailed(File)}. */
    public record InferResult(AST ast, Map<String, Object> pyAst, PythonInferenceResult inference) {
        public InferResult(AST ast, Map<String, Object> pyAst) {
            this(ast, pyAst, new PythonSemanticRefiner().refine(ast, pyAst, "<unknown>", null, Map.of()));
        }
    }

    /**
     * Demand-driven (call-site) inference: process the library source together with
     * a usage-context source in the same {@link ASF}.
     *
     * <p>Both ASTs are built and inferred jointly. Afterwards, the caller should use
     * {@link PythonAnnotationWriter#annotate(String, AST, AST)} or
     * {@link #inferWithContextDetailed} + {@link PythonAnnotationWriter#annotate(String, PythonInferenceResult)}
     * to produce annotations that include parameter types from call sites.
     *
     * @param librarySource  the library Python source (zero annotations)
     * @param usageSource    a Python snippet that calls the library with concrete arguments
     * @return a two-element array: {@code [libraryAst, usageAst]}
     */
    public AST[] inferWithContext(String librarySource, String usageSource) {
        return inferWithContextDetailed(librarySource, usageSource).asts();
    }

    /**
     * Like {@link #inferWithContext} but also returns a library-side
     * {@link PythonInferenceResult} whose {@link PythonInferenceResult#annotationHints()}
     * include call-site parameter types from the usage snippet.
     */
    public ContextInferResult inferWithContextDetailed(String librarySource, String usageSource) {
        converter.setModuleLoader(moduleRegistry.isEmpty() ? null : buildLoader(null));

        Map<String, Object> libPyAst = bridge.parse(librarySource);
        AST libAst = converter.convert(libPyAst);

        Map<String, Object> usagePyAst = bridge.parse(usageSource);
        AST usageAst = converter.convert(usagePyAst);

        asf.infer();

        PythonAnnotationWriter writer = new PythonAnnotationWriter();
        writer.propagateCallSiteTypes(libAst, usageAst);

        PythonInferenceResult refined = new PythonSemanticRefiner().refine(
                libAst, libPyAst, "<library>", null, moduleRegistry);
        Map<String, String> callHints = writer.collectCallSiteHints(libAst, usageAst);
        return new ContextInferResult(libAst, usageAst, refined, callHints);
    }

    /**
     * Joint library+usage inference: GCP ASTs, refined library facts, and
     * call-site annotation hints merged for {@link PythonAnnotationWriter}.
     */
    public record ContextInferResult(
            AST libraryAst,
            AST usageAst,
            PythonInferenceResult libraryInference,
            Map<String, String> callSiteHints) {
        public AST[] asts() {
            return new AST[]{libraryAst, usageAst};
        }

        /** Hints suitable for {@link PythonAnnotationWriter#annotate(String, AST, Map)}. */
        public Map<String, String> annotationHints() {
            Map<String, String> merged = new HashMap<>(libraryInference.annotationHints());
            merged.putAll(callSiteHints);
            return merged;
        }
    }

    // ── pipeline shortcuts ─────────────────────────────────────────────────────

    /**
     * Full pipeline: Python source → inferred + refined → {@code .pyi} stub string.
     */
    public String toStub(String pythonSource) {
        PythonInferenceResult result = inferDetailed(pythonSource);
        return generator.generate(result);
    }

    /**
     * Full pipeline for a Python file, writing the stub to {@code outputFile}.
     */
    public void annotate(File pythonFile, File outputFile) throws IOException {
        PythonInferenceResult result = inferFileDetailed(pythonFile).inference();
        String stub = generator.generate(result);
        Files.writeString(outputFile.toPath(), stub, StandardCharsets.UTF_8);
    }

    /**
     * Full pipeline for a Python file; stub is written alongside the source
     * as {@code <name>.pyi}.
     */
    public File annotate(File pythonFile) throws IOException {
        PythonInferenceResult result = inferFileDetailed(pythonFile).inference();
        String stub = generator.generate(result);
        String pyiName = pythonFile.getName().replaceAll("\\.py$", "") + ".pyi";
        File out = new File(pythonFile.getParentFile(), pyiName);
        Files.writeString(out.toPath(), stub, StandardCharsets.UTF_8);
        return out;
    }
}
