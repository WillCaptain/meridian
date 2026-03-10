package org.twelve.meridian.python;

import org.twelve.gcp.ast.ASF;
import org.twelve.gcp.ast.AST;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

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

    public PythonInferencer() {
        this.asf = new ASF();
        this.bridge = new PythonAstBridge();
        this.converter = new PythonGCPConverter(asf);
        this.generator = new TypeAnnotationGenerator();
    }

    // ── infer from string ──────────────────────────────────────────────────────

    /**
     * Parse and infer types for the given Python source code.
     *
     * @return the typed GCP AST (inference already applied)
     */
    public AST infer(String pythonSource) {
        Map<String, Object> pyAst = bridge.parse(pythonSource);
        AST ast = converter.convert(pyAst);
        asf.infer();
        return ast;
    }

    /**
     * Parse and infer types for a Python source file.
     */
    public AST inferFile(File pythonFile) throws IOException {
        Map<String, Object> pyAst = bridge.parseFile(pythonFile);
        AST ast = converter.convert(pyAst);
        asf.infer();
        return ast;
    }

    /**
     * Demand-driven (call-site) inference: process the library source together with
     * a usage-context source in the same {@link ASF}.
     *
     * <p>Both ASTs are built and inferred jointly. Afterwards, the caller should use
     * {@link PythonAnnotationWriter#annotate(String, AST, AST)} to produce annotations
     * that include parameter types extracted from the call sites in the usage AST.
     *
     * @param librarySource  the library Python source (zero annotations)
     * @param usageSource    a Python snippet that calls the library with concrete arguments
     * @return a two-element array: {@code [libraryAst, usageAst]}
     */
    public AST[] inferWithContext(String librarySource, String usageSource) {
        // Library AST first — its functions must be visible when usage is processed
        Map<String, Object> libPyAst = bridge.parse(librarySource);
        AST libAst = converter.convert(libPyAst);

        // Usage AST — call sites carry concrete argument types (literals → outlines)
        Map<String, Object> usagePyAst = bridge.parse(usageSource);
        AST usageAst = converter.convert(usagePyAst);

        // Joint inference: GCP's ASF infers over all registered ASTs simultaneously
        asf.infer();
        return new AST[]{libAst, usageAst};
    }

    // ── pipeline shortcuts ─────────────────────────────────────────────────────

    /**
     * Full pipeline: Python source → inferred GCP AST → {@code .pyi} stub string.
     */
    public String toStub(String pythonSource) {
        AST ast = infer(pythonSource);
        return generator.generate(ast);
    }

    /**
     * Full pipeline for a Python file, writing the stub to {@code outputFile}.
     */
    public void annotate(File pythonFile, File outputFile) throws IOException {
        AST ast = inferFile(pythonFile);
        String stub = generator.generate(ast);
        Files.writeString(outputFile.toPath(), stub, StandardCharsets.UTF_8);
    }

    /**
     * Full pipeline for a Python file; stub is written alongside the source
     * as {@code <name>.pyi}.
     */
    public File annotate(File pythonFile) throws IOException {
        AST ast = inferFile(pythonFile);
        String stub = generator.generate(ast);
        String pyiName = pythonFile.getName().replaceAll("\\.py$", "") + ".pyi";
        File out = new File(pythonFile.getParentFile(), pyiName);
        Files.writeString(out.toPath(), stub, StandardCharsets.UTF_8);
        return out;
    }
}
