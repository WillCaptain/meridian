package org.twelve.meridian.python;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Product <em>compile</em> surface: naked Python → annotate/specialize →
 * tree-shake unreachable defs → mypyc → optional native eval check.
 *
 * <p>Multi-module packages use {@link #runPackage(PackageRequest)} with
 * {@link MypycAnnotationPrep.Mode#KEEP_DEPS} and optional
 * {@link HotCompileSelector} import closure (L6 productized).
 *
 * <p>IDE hover facts (including {@code Union}/{@code Optional}) live on
 * {@link IdeTypeSurface} — not this pipeline.
 *
 * <p>With {@code benchCasesJson}, after compile:
 * <ol>
 *   <li>Eval correctness: Meridian(.so) result == native CPython result</li>
 *   <li>Eval performance: speedup vs native CPython</li>
 * </ol>
 */
public final class CompilePipeline {

    public record Request(
            String librarySource,
            String moduleName,
            String usageSource,          // nullable
            boolean specialize,
            AnnotationPolicy policy,
            Path outputDir,
            String benchCasesJson        // nullable — enables native eval check
    ) {}

    public record Outcome(
            String annotatedSource,
            Path annotatedFile,
            MypycRunner.CompileResult compileResult,
            String benchJson,            // nullable
            boolean benchOk,             // true when no bench, or all cases correct
            boolean specialized,
            Map<String, FunctionSpecializer.FuncSpecializations> plan,
            Set<String> prunedFunctions
    ) {}

    /**
     * Multi-module package compile (L5/L6 shape).
     *
     * @param compileModules explicit mypyc set; ignored when {@code compileImportClosure}
     * @param compileImportClosure when true, mypyc the import closure of primary
     *                             ({@link HotCompileSelector}); else empty list → all modules
     * @param annotationMode {@link MypycAnnotationPrep.Mode} wire name (default strip_deps)
     */
    public record PackageRequest(
            Map<String, String> modules,
            String primaryModule,
            String usageSource,
            List<String> compileModules,
            boolean compileImportClosure,
            String annotationMode,
            AnnotationPolicy policy,
            Path outputDir
    ) {
        public PackageRequest {
            if (compileModules == null) {
                compileModules = List.of();
            }
        }
    }

    public record PackageOutcome(
            Map<String, String> annotatedSources,
            Map<String, String> mypycSources,
            List<String> compileModules,
            MypycAnnotationPrep.Mode annotationMode,
            Path primaryFile,
            MypycRunner.CompileResult compileResult
    ) {}

    private final PythonInferencer inferencer = new PythonInferencer();
    private final PythonAnnotationWriter writer = new PythonAnnotationWriter();
    private final FunctionSpecializer specializer = new FunctionSpecializer();
    private final MypycRunner mypyc = new MypycRunner();

    public Outcome run(Request req) throws IOException {
        if (req.librarySource() == null || req.librarySource().isBlank()) {
            throw new IllegalArgumentException("librarySource");
        }
        if (req.moduleName() == null || req.moduleName().isBlank()) {
            throw new IllegalArgumentException("moduleName");
        }
        if (req.outputDir() == null) {
            throw new IllegalArgumentException("outputDir");
        }
        Files.createDirectories(req.outputDir());

        AnnotationPolicy policy = req.policy() == null
                ? AnnotationPolicy.defaultPolicy()
                : req.policy();
        writer.withPolicy(policy);

        String annotated;
        Map<String, FunctionSpecializer.FuncSpecializations> plan = Map.of();
        boolean specialized = false;
        Set<String> pruned = Set.of();

        String usage = req.usageSource();
        if (usage != null && !usage.isBlank()) {
            PythonInferencer.ContextInferResult ctx =
                    inferencer.inferWithContextDetailed(req.librarySource(), usage);
            if (req.specialize()) {
                plan = specializer.analyse(ctx.libraryAst(), ctx.usageAst());
                if (!plan.isEmpty()) {
                    annotated = specializer.specialize(
                            req.librarySource(), plan, ctx.libraryAst());
                    specialized = FunctionSpecializer.needsPolymorphicDispatch(plan)
                            || plan.values().stream().anyMatch(fs -> !fs.bindings().isEmpty());
                } else {
                    annotated = writer.annotate(req.librarySource(), ctx);
                }
            } else {
                annotated = writer.annotate(req.librarySource(), ctx);
            }
            // Compile surface: drop defs never reached from usage (keep callees).
            CompileSourcePruner.Result shake =
                    CompileSourcePruner.prune(annotated, ctx.libraryAst(), ctx.usageAst());
            annotated = shake.source();
            pruned = shake.removed();
        } else {
            // No usage → no tree-shake evidence; annotate whole module (SAFE_PARTIAL).
            PythonInferenceResult inferred = inferencer.inferDetailed(req.librarySource());
            annotated = writer.annotate(req.librarySource(), inferred);
        }

        Path annFile = req.outputDir().resolve(req.moduleName() + ".py");
        Files.writeString(annFile, annotated, StandardCharsets.UTF_8);

        MypycRunner.CompileResult compiled =
                mypyc.compile(annFile.toFile(), req.outputDir().toFile());

        String benchJson = null;
        boolean benchOk = true;
        if (req.benchCasesJson() != null && !req.benchCasesJson().isBlank()
                && compiled.success()) {
            BenchRun bench = runBenchmark(req, compiled);
            benchJson = bench.json();
            benchOk = bench.ok();
        }

        return new Outcome(annotated, annFile, compiled, benchJson, benchOk,
                specialized, plan, pruned);
    }

    /**
     * Annotate every package module with shared usage, prepare for mypyc via
     * {@link MypycAnnotationPrep}, materialize all modules, compile the hot set.
     */
    public PackageOutcome runPackage(PackageRequest req) throws IOException {
        if (req.modules() == null || req.modules().isEmpty()) {
            throw new IllegalArgumentException("modules");
        }
        if (req.primaryModule() == null || req.primaryModule().isBlank()) {
            throw new IllegalArgumentException("primaryModule");
        }
        if (!req.modules().containsKey(req.primaryModule())) {
            throw new IllegalArgumentException("primary module missing: " + req.primaryModule());
        }
        if (req.outputDir() == null) {
            throw new IllegalArgumentException("outputDir");
        }
        Files.createDirectories(req.outputDir());

        AnnotationPolicy policy = req.policy() == null
                ? AnnotationPolicy.defaultPolicy()
                : req.policy();
        writer.withPolicy(policy);

        String usage = req.usageSource() == null ? "" : req.usageSource();
        Map<String, String> annotated = annotatePackage(req.modules(), usage);

        MypycAnnotationPrep.Mode mode = MypycAnnotationPrep.Mode.parse(req.annotationMode());
        Map<String, String> prepared = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : annotated.entrySet()) {
            prepared.put(e.getKey(), MypycAnnotationPrep.prepare(
                    e.getKey(), e.getValue(), req.primaryModule(), mode));
        }

        List<String> compileNames;
        if (req.compileImportClosure()) {
            compileNames = HotCompileSelector.importClosure(req.primaryModule(), req.modules());
        } else if (req.compileModules() == null || req.compileModules().isEmpty()) {
            compileNames = new ArrayList<>(req.modules().keySet());
        } else {
            compileNames = new ArrayList<>(req.compileModules());
        }

        for (Map.Entry<String, String> e : prepared.entrySet()) {
            Files.writeString(req.outputDir().resolve(e.getKey() + ".py"), e.getValue(),
                    StandardCharsets.UTF_8);
        }

        List<File> compileFiles = new ArrayList<>();
        File primaryFile = null;
        for (String name : compileNames) {
            if (!prepared.containsKey(name)) {
                throw new IllegalArgumentException("compile module missing: " + name);
            }
            File f = req.outputDir().resolve(name + ".py").toFile();
            compileFiles.add(f);
            if (name.equals(req.primaryModule())) {
                primaryFile = f;
            }
        }
        if (primaryFile == null) {
            throw new IllegalArgumentException(
                    "primary must be in compile set: " + req.primaryModule());
        }

        MypycRunner.CompileResult compiled =
                mypyc.compile(compileFiles, req.outputDir().toFile(), primaryFile);

        return new PackageOutcome(annotated, prepared, List.copyOf(compileNames), mode,
                primaryFile.toPath(), compiled);
    }

    private Map<String, String> annotatePackage(Map<String, String> modules, String usage)
            throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : modules.keySet()) {
            PythonInferencer inf = new PythonInferencer();
            for (Map.Entry<String, String> e : modules.entrySet()) {
                if (!e.getKey().equals(name)) {
                    inf.registerModule(e.getKey(), e.getValue());
                }
            }
            if (usage != null && !usage.isBlank()) {
                PythonInferencer.ContextInferResult ctx =
                        inf.inferWithContextDetailed(modules.get(name), usage);
                out.put(name, writer.annotate(modules.get(name), ctx));
            } else {
                PythonInferenceResult inferred = inf.inferDetailed(modules.get(name));
                out.put(name, writer.annotate(modules.get(name), inferred));
            }
        }
        return out;
    }

    private record BenchRun(String json, boolean ok) {}

    /**
     * Layout in outputDir:
     * <ul>
     *   <li>{@code <module>_native.py} — naked source (CPython baseline)</li>
     *   <li>{@code <module>_native.so} — optional mypyc(bare) control</li>
     *   <li>{@code <module>.py} + {@code <module>.so} — Meridian annotated</li>
     * </ul>
     */
    private BenchRun runBenchmark(Request req, MypycRunner.CompileResult annCompiled)
            throws IOException {
        String nativeName = req.moduleName() + "_native";
        String meridianName = req.moduleName();

        Path nativePy = req.outputDir().resolve(nativeName + ".py");
        Files.writeString(nativePy, req.librarySource(), StandardCharsets.UTF_8);

        // Optional control lane: mypyc on naked source (same module as native).
        MypycRunner.CompileResult bareCompiled =
                mypyc.compile(nativePy.toFile(), req.outputDir().toFile());
        if (!bareCompiled.success()) {
            // Still allow native-vs-Meridian; generic_benchmark treats missing bare.so as 2-way.
            System.err.println("note: mypyc(bare) control compile failed; "
                    + "bench will compare native vs Meridian only");
        }

        if (annCompiled.outputFile() != null) {
            Path dest = req.outputDir().resolve(annCompiled.outputFile().getName());
            if (!dest.toAbsolutePath().equals(annCompiled.outputFile().toPath().toAbsolutePath())) {
                Files.copy(annCompiled.outputFile().toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        Path benchScript = req.outputDir().resolve("generic_benchmark.py");
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("generic_benchmark.py")) {
            if (is == null) {
                return new BenchRun("{\"error\":\"generic_benchmark.py resource missing\",\"ok\":false}",
                        false);
            }
            Files.copy(is, benchScript, StandardCopyOption.REPLACE_EXISTING);
        }

        String python = detectPython();
        ProcessBuilder pb = new ProcessBuilder(
                python,
                benchScript.toAbsolutePath().toString(),
                req.outputDir().toAbsolutePath().toString(),
                nativeName,
                meridianName,
                req.benchCasesJson());
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        byte[] stdout;
        byte[] stderr;
        try {
            stdout = proc.getInputStream().readAllBytes();
            stderr = proc.getErrorStream().readAllBytes();
            if (!proc.waitFor(180, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return new BenchRun("{\"error\":\"benchmark timed out\",\"ok\":false}", false);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BenchRun("{\"error\":\"benchmark interrupted\",\"ok\":false}", false);
        }

        String out = new String(stdout, StandardCharsets.UTF_8).trim();
        String err = new String(stderr, StandardCharsets.UTF_8).trim();
        if (out.isBlank() && !err.isBlank()) {
            out = "{\"error\":" + jsonEscape(err) + ",\"ok\":false}";
        }
        boolean ok = proc.exitValue() == 0 && !out.contains("\"ok\": false")
                && !out.contains("\"ok\":false");
        if (proc.exitValue() != 0 && out.isBlank()) {
            out = "{\"error\":" + jsonEscape(err) + ",\"ok\":false}";
            ok = false;
        }
        return new BenchRun(out, ok);
    }

    private static String detectPython() {
        String env = System.getenv("PYTHON_BIN");
        if (env != null && !env.isBlank()) return env;
        for (String c : new String[]{"/opt/homebrew/bin/python3", "/usr/local/bin/python3", "python3"}) {
            if (c.equals("python3") || new File(c).exists()) return c;
        }
        return "python3";
    }

    private static String jsonEscape(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
