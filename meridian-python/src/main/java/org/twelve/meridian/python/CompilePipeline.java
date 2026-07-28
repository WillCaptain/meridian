package org.twelve.meridian.python;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Product <em>compile</em> surface: naked Python → annotate/specialize →
 * tree-shake unreachable defs → mypyc → optional native eval check.
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
