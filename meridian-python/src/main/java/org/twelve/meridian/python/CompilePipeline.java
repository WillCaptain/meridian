package org.twelve.meridian.python;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Product compile path: naked Python → annotate (/specialize) → mypyc → optional bench.
 *
 * <p>Reuses existing inferencer, annotation writer, {@link FunctionSpecializer},
 * and {@link MypycRunner}. This is the CLI/orchestration layer over work already
 * proven in {@code ConverterE2ETest} / {@code MonomorphizationTest}.
 */
public final class CompilePipeline {

    public record Request(
            String librarySource,
            String moduleName,
            String usageSource,          // nullable — enables demand-driven annotate / specialize
            boolean specialize,          // when usage present: monomorphize multi-type call sites
            AnnotationPolicy policy,
            Path outputDir,
            String benchCasesJson        // nullable — JSON for generic_benchmark.py
    ) {}

    public record Outcome(
            String annotatedSource,
            Path annotatedFile,
            MypycRunner.CompileResult compileResult,
            String benchJson,            // nullable
            boolean specialized,
            Map<String, FunctionSpecializer.FuncSpecializations> plan
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

        String usage = req.usageSource();
        if (usage != null && !usage.isBlank()) {
            PythonInferencer.ContextInferResult ctx =
                    inferencer.inferWithContextDetailed(req.librarySource(), usage);
            if (req.specialize()) {
                plan = specializer.analyse(ctx.libraryAst(), ctx.usageAst());
                if (!plan.isEmpty()) {
                    annotated = specializer.specialize(req.librarySource(), plan);
                    specialized = FunctionSpecializer.needsPolymorphicDispatch(plan)
                            || plan.values().stream().anyMatch(fs -> !fs.bindings().isEmpty());
                } else {
                    annotated = writer.annotate(req.librarySource(), ctx);
                }
            } else {
                annotated = writer.annotate(req.librarySource(), ctx);
            }
        } else {
            PythonInferenceResult inferred = inferencer.inferDetailed(req.librarySource());
            annotated = writer.annotate(req.librarySource(), inferred);
        }

        Path annFile = req.outputDir().resolve(req.moduleName() + ".py");
        Files.writeString(annFile, annotated, StandardCharsets.UTF_8);

        MypycRunner.CompileResult compiled =
                mypyc.compile(annFile.toFile(), req.outputDir().toFile());

        String benchJson = null;
        if (req.benchCasesJson() != null && !req.benchCasesJson().isBlank()
                && compiled.success()) {
            benchJson = runBenchmark(req, annFile, compiled);
        }

        return new Outcome(annotated, annFile, compiled, benchJson, specialized, plan);
    }

    private String runBenchmark(Request req, Path annFile, MypycRunner.CompileResult annCompiled)
            throws IOException {
        // Bare control: compile naked library under a distinct module name.
        String bareName = req.moduleName() + "_bare";
        Path barePy = req.outputDir().resolve(bareName + ".py");
        Files.writeString(barePy, req.librarySource(), StandardCharsets.UTF_8);
        MypycRunner.CompileResult bareCompiled =
                mypyc.compile(barePy.toFile(), req.outputDir().toFile());
        if (!bareCompiled.success()) {
            return "{\"error\":\"bare mypyc compile failed\",\"stderr\":"
                    + jsonEscape(bareCompiled.stderr()) + "}";
        }

        // Ensure annotated .py name matches generic_benchmark expectations:
        // work_dir contains <bare>.py, <ann>.py, both .so files.
        // We already wrote <module>.py as annotated; also keep a copy as <module>_gcp.py
        // when module name equals bare — use distinct names.
        String gcpName = req.moduleName();
        // Re-copy native artifacts next to sources (compile already wrote into outputDir).
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
                return "{\"error\":\"generic_benchmark.py resource missing\"}";
            }
            Files.copy(is, benchScript, StandardCopyOption.REPLACE_EXISTING);
        }

        String python = detectPython();
        ProcessBuilder pb = new ProcessBuilder(
                python,
                benchScript.toAbsolutePath().toString(),
                req.outputDir().toAbsolutePath().toString(),
                bareName,
                gcpName,
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
                return "{\"error\":\"benchmark timed out\"}";
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\":\"benchmark interrupted\"}";
        }
        if (proc.exitValue() != 0) {
            return "{\"error\":\"benchmark failed\",\"stderr\":"
                    + jsonEscape(new String(stderr, StandardCharsets.UTF_8)) + "}";
        }
        return new String(stdout, StandardCharsets.UTF_8);
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
