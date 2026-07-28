package org.twelve.meridian.python.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.twelve.meridian.python.AnnotationPolicy;
import org.twelve.meridian.python.CompilePipeline;
import org.twelve.meridian.python.PythonAnnotationWriter;
import org.twelve.meridian.python.PythonInferencer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Four-gate production-corpus proof:
 * coverage → mypyc compile → native correctness → speedup vs native.
 */
public final class CorpusProofRunner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record Spec(
            String corpusId,
            String title,
            String moduleName,
            String librarySource,
            String coverageUsage,
            String benchUsage,
            String benchCasesJson,
            double minParamCoverage,
            double minAvgSpeedup
    ) {}

    public record BenchRow(
            String func,
            boolean correct,
            double speedupVsNative,
            double nativeNs,
            double meridianNs
    ) {}

    public record Report(
            String corpusId,
            String title,
            AnnotationCoverage.Stats coverage,
            boolean compileOk,
            String compileError,
            boolean benchOk,
            double correctRate,
            double avgSpeedup,
            List<BenchRow> rows,
            String annotatedSource,
            List<String> pruned
    ) {
        public boolean gatesPass(double minParamCoverage, double minAvgSpeedup) {
            return coverage.paramCoverage() + 1e-9 >= minParamCoverage
                    && compileOk
                    && correctRate + 1e-9 >= 1.0
                    && avgSpeedup + 1e-9 >= minAvgSpeedup;
        }
    }

    private final CompilePipeline pipeline = new CompilePipeline();
    private final PythonInferencer inferencer = new PythonInferencer();
    private final PythonAnnotationWriter writer = new PythonAnnotationWriter();

    public Report evaluate(Spec spec, Path workDir) throws IOException {
        Files.createDirectories(workDir);

        // Gate 1 — coverage on full usage (including leave-gap probes).
        writer.withPolicy(AnnotationPolicy.SAFE_PARTIAL);
        PythonInferencer.ContextInferResult ctx =
                inferencer.inferWithContextDetailed(spec.librarySource(), spec.coverageUsage());
        String annotatedForCoverage = writer.annotate(spec.librarySource(), ctx);
        AnnotationCoverage.Stats cov = AnnotationCoverage.measure(annotatedForCoverage);

        // Gates 2–4 — product compile path + native bench.
        CompilePipeline.Outcome outcome = pipeline.run(new CompilePipeline.Request(
                spec.librarySource(),
                spec.moduleName(),
                spec.benchUsage(),
                true,
                AnnotationPolicy.ALL_CONCRETE,
                workDir,
                spec.benchCasesJson()
        ));

        boolean compileOk = outcome.compileResult() != null && outcome.compileResult().success();
        String compileErr = compileOk ? null : (
                outcome.compileResult() == null ? "no compile result"
                        : outcome.compileResult().stderr());

        List<BenchRow> rows = new ArrayList<>();
        double correctRate = 0;
        double avgSpeedup = 0;
        if (compileOk && outcome.benchJson() != null && !outcome.benchJson().isBlank()) {
            JsonNode root = MAPPER.readTree(outcome.benchJson());
            JsonNode arr = root.path("rows");
            int ok = 0;
            double sumSp = 0;
            if (arr.isArray()) {
                for (JsonNode r : arr) {
                    boolean correct = r.path("correct").asBoolean(false);
                    double sp = r.path("speedup_vs_native").asDouble(
                            r.path("speedup_gcp").asDouble(0));
                    double nativeNs = r.path("native_ns").asDouble(
                            r.path("cpython_ns").asDouble(0));
                    double merNs = r.path("meridian_ns").asDouble(
                            r.path("mypyc_gcp_ns").asDouble(0));
                    String func = r.path("func").asText("?");
                    rows.add(new BenchRow(func, correct, sp, nativeNs, merNs));
                    if (correct) ok++;
                    sumSp += sp;
                }
                int n = arr.size();
                if (n > 0) {
                    correctRate = (double) ok / n;
                    avgSpeedup = sumSp / n;
                }
            }
        }

        return new Report(
                spec.corpusId(),
                spec.title(),
                cov,
                compileOk,
                compileErr,
                outcome.benchOk(),
                correctRate,
                avgSpeedup,
                rows,
                outcome.annotatedSource(),
                List.copyOf(outcome.prunedFunctions())
        );
    }

    public static void archive(Report report, Spec spec) throws IOException {
        List<Map<String, Object>> rowMaps = new ArrayList<>();
        for (BenchRow r : report.rows()) {
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
                "Third-party-shaped pure-Python subset; not full-package import graph");
        payload.put("funcs_total", report.coverage().funcsTotal());
        payload.put("param_coverage", round3(report.coverage().paramCoverage()));
        payload.put("return_coverage", round3(report.coverage().returnCoverage()));
        payload.put("params_annotated", report.coverage().paramsAnnotated());
        payload.put("params_total", report.coverage().paramsTotal());
        payload.put("partially_unannotated_funcs", report.coverage().unannotatedFuncs());
        payload.put("mypyc_compile_ok", report.compileOk());
        payload.put("correct_rate", round3(report.correctRate()));
        payload.put("avg_speedup_vs_native", round3(report.avgSpeedup()));
        payload.put("gates_pass", report.gatesPass(spec.minParamCoverage(), spec.minAvgSpeedup()));
        payload.put("pruned", report.pruned());
        payload.put("rows", rowMaps);

        String body = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);

        StringBuilder md = new StringBuilder();
        md.append("## Summary\n\n");
        md.append(String.format(Locale.US,
                "- param coverage: **%.1f%%** (%d / %d)\n",
                100 * report.coverage().paramCoverage(),
                report.coverage().paramsAnnotated(),
                report.coverage().paramsTotal()));
        md.append(String.format(Locale.US,
                "- return coverage: **%.1f%%**\n",
                100 * report.coverage().returnCoverage()));
        md.append(String.format(Locale.US,
                "- mypyc compile: **%s**\n", report.compileOk() ? "ok" : "FAIL"));
        md.append(String.format(Locale.US,
                "- correct rate: **%.0f%%**\n", 100 * report.correctRate()));
        md.append(String.format(Locale.US,
                "- avg speedup vs native: **%.2f×**\n", report.avgSpeedup()));
        md.append(String.format(Locale.US,
                "- gates pass: **%s** (min param≥%.0f%%, avg speedup≥%.1f×)\n\n",
                report.gatesPass(spec.minParamCoverage(), spec.minAvgSpeedup()),
                100 * spec.minParamCoverage(),
                spec.minAvgSpeedup()));
        if (!report.coverage().unannotatedFuncs().isEmpty()) {
            md.append("- partially unannotated (leave-gap ok): `")
                    .append(String.join("`, `", report.coverage().unannotatedFuncs()))
                    .append("`\n\n");
        }
        md.append("| Function | correct | native ns | Meridian ns | Meridian× |\n");
        md.append("|----------|---------|-----------|-------------|----------|\n");
        for (BenchRow r : report.rows()) {
            md.append(String.format(Locale.US,
                    "| `%s` | %s | %.1f | %.1f | %.2f |\n",
                    r.func(), r.correct(), r.nativeNs(), r.meridianNs(), r.speedupVsNative()));
        }

        EvalResultArchive.writeSuite(
                report.corpusId(),
                report.title(),
                body,
                md.toString());
    }

    /** Load a corpus directory: recipes.py / calls.py / calls_bench.py / cases.json / manifest.json */
    public static Spec loadDir(Path dir) throws IOException {
        String lib = Files.readString(dir.resolve("recipes.py"), StandardCharsets.UTF_8);
        String coverageUsage = Files.readString(dir.resolve("calls.py"), StandardCharsets.UTF_8);
        Path benchCalls = dir.resolve("calls_bench.py");
        String benchUsage = Files.exists(benchCalls)
                ? Files.readString(benchCalls, StandardCharsets.UTF_8)
                : coverageUsage;
        String cases = Files.readString(dir.resolve("cases.json"), StandardCharsets.UTF_8).trim();
        JsonNode man = MAPPER.readTree(
                Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8));
        double minCov = man.path("gates").path("min_param_coverage").asDouble(0.70);
        double minSp = man.path("gates").path("min_avg_speedup_vs_native").asDouble(2.0);
        return new Spec(
                man.path("corpus_id").asText(dir.getFileName().toString()),
                man.path("title").asText("Production corpus"),
                man.path("module_name").asText("recipes"),
                lib,
                coverageUsage,
                benchUsage,
                cases,
                minCov,
                minSp
        );
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
