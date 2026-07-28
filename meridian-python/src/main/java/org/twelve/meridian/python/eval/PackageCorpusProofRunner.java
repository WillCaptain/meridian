package org.twelve.meridian.python.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.twelve.meridian.python.AnnotationPolicy;
import org.twelve.meridian.python.MypycRunner;
import org.twelve.meridian.python.PythonAnnotationWriter;
import org.twelve.meridian.python.PythonInferencer;

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
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * L3 multi-module corpus proof: registerModule import graph → annotate each
 * module → mypyc multi-file compile → native correctness / speedup.
 */
public final class PackageCorpusProofRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record PackageSpec(
            String corpusId,
            String title,
            String primaryModule,
            Map<String, String> modules,
            String coverageUsage,
            String benchUsage,
            String benchCasesJson,
            double minParamCoverage,
            double minAvgSpeedup
    ) {}

    private final MypycRunner mypyc = new MypycRunner();
    private final PythonAnnotationWriter writer = new PythonAnnotationWriter();

    public CorpusProofRunner.Report evaluate(PackageSpec spec, Path workDir) throws IOException {
        Files.createDirectories(workDir);
        Path nativeDir = workDir.resolve("native");
        Path meridianDir = workDir.resolve("meridian");
        Files.createDirectories(nativeDir);
        Files.createDirectories(meridianDir);

        // Naked copies for native lane.
        for (Map.Entry<String, String> e : spec.modules().entrySet()) {
            Files.writeString(nativeDir.resolve(e.getKey() + ".py"), e.getValue(),
                    StandardCharsets.UTF_8);
        }

        // Gate 1 — annotate every module with shared usage (import graph).
        writer.withPolicy(AnnotationPolicy.SAFE_PARTIAL);
        Map<String, String> annotatedCoverage = annotateAll(spec.modules(), spec.coverageUsage());
        AnnotationCoverage.Stats cov = aggregateCoverage(annotatedCoverage);

        // Gates 2–4 — ALL_CONCRETE annotate + multi-file mypyc + bench.
        writer.withPolicy(AnnotationPolicy.ALL_CONCRETE);
        Map<String, String> annotatedCompile = annotateAll(spec.modules(), spec.benchUsage());
        List<File> compileFiles = new ArrayList<>();
        File primaryFile = null;
        for (Map.Entry<String, String> e : annotatedCompile.entrySet()) {
            Path p = meridianDir.resolve(e.getKey() + ".py");
            Files.writeString(p, e.getValue(), StandardCharsets.UTF_8);
            compileFiles.add(p.toFile());
            if (e.getKey().equals(spec.primaryModule())) {
                primaryFile = p.toFile();
            }
        }
        if (primaryFile == null) {
            throw new IllegalArgumentException("primary module missing: " + spec.primaryModule());
        }

        MypycRunner.CompileResult compiled =
                mypyc.compile(compileFiles, meridianDir.toFile(), primaryFile);

        boolean compileOk = compiled.success();
        String compileErr = compileOk ? null : compiled.stderr();

        List<CorpusProofRunner.BenchRow> rows = new ArrayList<>();
        double correctRate = 0;
        double avgSpeedup = 0;
        boolean benchOk = false;
        if (compileOk && spec.benchCasesJson() != null && !spec.benchCasesJson().isBlank()) {
            String benchJson = runPackageBenchmark(workDir, spec.primaryModule(),
                    spec.benchCasesJson());
            benchOk = benchJson != null
                    && !benchJson.contains("\"ok\": false")
                    && !benchJson.contains("\"ok\":false");
            if (benchJson != null && !benchJson.isBlank()) {
                JsonNode root = MAPPER.readTree(benchJson);
                JsonNode arr = root.path("rows");
                int ok = 0;
                double sumSp = 0;
                if (arr.isArray()) {
                    for (JsonNode r : arr) {
                        boolean correct = r.path("correct").asBoolean(false);
                        double sp = r.path("speedup_vs_native").asDouble(
                                r.path("speedup_gcp").asDouble(0));
                        rows.add(new CorpusProofRunner.BenchRow(
                                r.path("func").asText("?"),
                                correct,
                                sp,
                                r.path("native_ns").asDouble(r.path("cpython_ns").asDouble(0)),
                                r.path("meridian_ns").asDouble(r.path("mypyc_gcp_ns").asDouble(0))
                        ));
                        if (correct) ok++;
                        sumSp += sp;
                    }
                    int n = arr.size();
                    if (n > 0) {
                        correctRate = (double) ok / n;
                        avgSpeedup = sumSp / n;
                        benchOk = benchOk && ok == n;
                    }
                }
            }
        }

        String primaryAnn = annotatedCompile.getOrDefault(spec.primaryModule(), "");
        return new CorpusProofRunner.Report(
                spec.corpusId(),
                spec.title(),
                cov,
                compileOk,
                compileErr,
                benchOk,
                correctRate,
                avgSpeedup,
                rows,
                primaryAnn,
                List.of()
        );
    }

    private Map<String, String> annotateAll(Map<String, String> modules, String usage)
            throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String name : modules.keySet()) {
            PythonInferencer inf = new PythonInferencer();
            for (Map.Entry<String, String> e : modules.entrySet()) {
                if (!e.getKey().equals(name)) {
                    inf.registerModule(e.getKey(), e.getValue());
                }
            }
            PythonInferencer.ContextInferResult ctx =
                    inf.inferWithContextDetailed(modules.get(name), usage);
            out.put(name, writer.annotate(modules.get(name), ctx));
        }
        return out;
    }

    private static AnnotationCoverage.Stats aggregateCoverage(Map<String, String> annotated) {
        int funcs = 0, withRet = 0, params = 0, ann = 0;
        List<String> unannotated = new ArrayList<>();
        Map<String, Boolean> retMap = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : annotated.entrySet()) {
            AnnotationCoverage.Stats s = AnnotationCoverage.measure(e.getValue());
            funcs += s.funcsTotal();
            withRet += s.funcsWithReturn();
            params += s.paramsTotal();
            ann += s.paramsAnnotated();
            for (String u : s.unannotatedFuncs()) {
                unannotated.add(e.getKey() + "." + u);
            }
            retMap.putAll(s.returnAnnotatedByFunc());
        }
        return new AnnotationCoverage.Stats(funcs, withRet, params, ann, unannotated, retMap);
    }

    private String runPackageBenchmark(Path workDir, String primary, String casesJson)
            throws IOException {
        Path benchScript = workDir.resolve("generic_benchmark.py");
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("generic_benchmark.py")) {
            if (is == null) {
                return "{\"error\":\"generic_benchmark.py missing\",\"ok\":false}";
            }
            Files.copy(is, benchScript, StandardCopyOption.REPLACE_EXISTING);
        }
        String python = detectPython();
        // native_module name == meridian primary (same import name; different dirs)
        ProcessBuilder pb = new ProcessBuilder(
                python,
                benchScript.toAbsolutePath().toString(),
                workDir.toAbsolutePath().toString(),
                primary,
                primary,
                casesJson);
        pb.redirectErrorStream(false);
        Process proc = pb.start();
        try {
            byte[] stdout = proc.getInputStream().readAllBytes();
            byte[] stderr = proc.getErrorStream().readAllBytes();
            if (!proc.waitFor(180, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return "{\"error\":\"benchmark timed out\",\"ok\":false}";
            }
            String out = new String(stdout, StandardCharsets.UTF_8).trim();
            String err = new String(stderr, StandardCharsets.UTF_8).trim();
            if (out.isBlank() && !err.isBlank()) {
                return "{\"error\":" + jsonEscape(err) + ",\"ok\":false}";
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "{\"error\":\"benchmark interrupted\",\"ok\":false}";
        }
    }

    public static PackageSpec loadDir(Path dir) throws IOException {
        JsonNode man = MAPPER.readTree(
                Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8));
        Path pkg = dir.resolve(man.path("package_dir").asText("package"));
        Map<String, String> modules = new LinkedHashMap<>();
        for (JsonNode n : man.path("modules")) {
            String name = n.asText();
            modules.put(name, Files.readString(pkg.resolve(name + ".py"), StandardCharsets.UTF_8));
        }
        String coverage = Files.readString(dir.resolve("calls.py"), StandardCharsets.UTF_8);
        Path benchCalls = dir.resolve("calls_bench.py");
        String benchUsage = Files.exists(benchCalls)
                ? Files.readString(benchCalls, StandardCharsets.UTF_8)
                : coverage;
        String cases = Files.readString(dir.resolve("cases.json"), StandardCharsets.UTF_8).trim();
        return new PackageSpec(
                man.path("corpus_id").asText(dir.getFileName().toString()),
                man.path("title").asText("L3 package corpus"),
                man.path("primary_module").asText(),
                modules,
                coverage,
                benchUsage,
                cases,
                man.path("gates").path("min_param_coverage").asDouble(0.70),
                man.path("gates").path("min_avg_speedup_vs_native").asDouble(2.0)
        );
    }

    public static void archive(CorpusProofRunner.Report report, PackageSpec spec)
            throws IOException {
        List<Map<String, Object>> rowMaps = new ArrayList<>();
        for (CorpusProofRunner.BenchRow r : report.rows()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("func", r.func());
            m.put("correct", r.correct());
            m.put("native_ns", Math.round(r.nativeNs() * 10.0) / 10.0);
            m.put("meridian_ns", Math.round(r.meridianNs() * 10.0) / 10.0);
            m.put("speedup_vs_native", Math.round(r.speedupVsNative() * 100.0) / 100.0);
            rowMaps.add(m);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("corpus", report.corpusId());
        payload.put("claim_boundary",
                "Multi-module import graph; adapted list hot paths; not full upstream more.py");
        payload.put("modules", new ArrayList<>(spec.modules().keySet()));
        payload.put("primary_module", spec.primaryModule());
        payload.put("funcs_total", report.coverage().funcsTotal());
        payload.put("param_coverage", round3(report.coverage().paramCoverage()));
        payload.put("return_coverage", round3(report.coverage().returnCoverage()));
        payload.put("mypyc_compile_ok", report.compileOk());
        payload.put("correct_rate", round3(report.correctRate()));
        payload.put("avg_speedup_vs_native", round3(report.avgSpeedup()));
        payload.put("gates_pass", report.gatesPass(spec.minParamCoverage(), spec.minAvgSpeedup()));
        payload.put("partially_unannotated_funcs", report.coverage().unannotatedFuncs());
        payload.put("rows", rowMaps);

        String body = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
        StringBuilder md = new StringBuilder();
        md.append("## Summary\n\n");
        md.append(String.format(Locale.US,
                "- modules: `%s` (primary `%s`)\n",
                String.join("`, `", spec.modules().keySet()), spec.primaryModule()));
        md.append(String.format(Locale.US,
                "- param coverage: **%.1f%%** (%d / %d)\n",
                100 * report.coverage().paramCoverage(),
                report.coverage().paramsAnnotated(),
                report.coverage().paramsTotal()));
        md.append(String.format(Locale.US,
                "- return coverage: **%.1f%%**\n",
                100 * report.coverage().returnCoverage()));
        md.append(String.format(Locale.US,
                "- mypyc multi-file compile: **%s**\n", report.compileOk() ? "ok" : "FAIL"));
        md.append(String.format(Locale.US,
                "- correct rate: **%.0f%%**\n", 100 * report.correctRate()));
        md.append(String.format(Locale.US,
                "- avg speedup vs native: **%.2f×**\n", report.avgSpeedup()));
        md.append(String.format(Locale.US,
                "- gates pass: **%s**\n\n",
                report.gatesPass(spec.minParamCoverage(), spec.minAvgSpeedup())));
        md.append("| Function | correct | native ns | Meridian ns | Meridian× |\n");
        md.append("|----------|---------|-----------|-------------|----------|\n");
        for (CorpusProofRunner.BenchRow r : report.rows()) {
            md.append(String.format(Locale.US,
                    "| `%s` | %s | %.1f | %.1f | %.2f |\n",
                    r.func(), r.correct(), r.nativeNs(), r.meridianNs(), r.speedupVsNative()));
        }
        EvalResultArchive.writeSuite(report.corpusId(), report.title(), body, md.toString());
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
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
