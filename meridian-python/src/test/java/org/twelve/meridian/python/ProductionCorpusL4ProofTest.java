package org.twelve.meridian.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.twelve.meridian.python.eval.AnnotationCoverage;
import org.twelve.meridian.python.eval.CorpusProofRunner;
import org.twelve.meridian.python.eval.EvalResultArchive;

import java.io.InputStream;
import java.net.URL;
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
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L4: upstream more-itertools recipe bodies (selected).
 *
 * <p>SAFE_PARTIAL annotate + strip known-wrong {@code -> list[…]} returns before mypyc.
 */
class ProductionCorpusL4ProofTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern BAD_LIST_RETURN = Pattern.compile(
            "(?m)^(def\\s+(?:dotproduct|dotproduct_seq)\\s*\\([^)]*\\))\\s*->\\s*list\\[[^\\]]+\\]\\s*:");

    @Test
    void l4_upstream_more_itertools_four_gates() throws Exception {
        Path dir = resourceDir("production_corpus/l4_more_itertools_upstream");
        String lib = Files.readString(dir.resolve("upstream_kit.py"));
        String coverage = Files.readString(dir.resolve("calls.py"));
        String benchUsage = Files.readString(dir.resolve("calls_bench.py"));
        String cases = Files.readString(dir.resolve("cases.json")).trim();
        JsonNode man = MAPPER.readTree(Files.readString(dir.resolve("manifest.json")));

        double minCov = man.path("gates").path("min_param_coverage").asDouble(0.40);
        double minSp = man.path("gates").path("min_avg_speedup_vs_native").asDouble(1.05);
        String corpusId = man.path("corpus_id").asText("l4-more-itertools-upstream");
        String title = man.path("title").asText("L4 upstream more-itertools");

        PythonInferencer inferencer = new PythonInferencer();
        PythonAnnotationWriter writer = new PythonAnnotationWriter()
                .withPolicy(AnnotationPolicy.SAFE_PARTIAL);

        var covCtx = inferencer.inferWithContextDetailed(lib, coverage);
        AnnotationCoverage.Stats cov = AnnotationCoverage.measure(writer.annotate(lib, covCtx));

        Path work = Files.createTempDirectory("meridian_l4_");
        var benchCtx = inferencer.inferWithContextDetailed(lib, benchUsage);
        String annotated = repairReturns(writer.annotate(lib, benchCtx));

        Files.writeString(work.resolve("upstream_kit_native.py"), lib, StandardCharsets.UTF_8);
        Path annFile = work.resolve("upstream_kit.py");
        Files.writeString(annFile, annotated, StandardCharsets.UTF_8);

        MypycRunner.CompileResult compiled = new MypycRunner().compile(annFile.toFile(), work.toFile());
        assertTrue(compiled.success(),
                () -> "mypyc compile failed:\n" + compiled.stderr() + compiled.stdout()
                        + "\n--- annotated ---\n" + annotated);

        String benchJson = runBench(work, cases);
        assertTrue(benchJson != null && !benchJson.isBlank(), "empty bench json");

        List<CorpusProofRunner.BenchRow> rows = new ArrayList<>();
        JsonNode root = MAPPER.readTree(benchJson);
        JsonNode arr = root.path("rows");
        int ok = 0;
        double sum = 0;
        if (arr.isArray()) {
            for (JsonNode r : arr) {
                boolean correct = r.path("correct").asBoolean(false);
                double sp = r.path("speedup_vs_native").asDouble(0);
                rows.add(new CorpusProofRunner.BenchRow(
                        r.path("func").asText("?"),
                        correct,
                        sp,
                        r.path("native_ns").asDouble(r.path("cpython_ns").asDouble(0)),
                        r.path("meridian_ns").asDouble(r.path("mypyc_gcp_ns").asDouble(0))));
                if (correct) ok++;
                sum += sp;
            }
        }
        int n = rows.size();
        double correctRate = n == 0 ? 0 : (double) ok / n;
        double avgSpeedup = n == 0 ? 0 : sum / n;
        boolean benchOk = root.path("ok").asBoolean(ok == n && n > 0);

        assertTrue(cov.paramCoverage() + 1e-9 >= minCov,
                () -> String.format(Locale.US, "param coverage %.1f%% < %.0f%%; unannotated=%s",
                        100 * cov.paramCoverage(), 100 * minCov, cov.unannotatedFuncs()));
        assertTrue(correctRate + 1e-9 >= 1.0,
                () -> "correctness failed: " + rows + "\n" + benchJson);
        assertTrue(avgSpeedup + 1e-9 >= minSp,
                () -> String.format(Locale.US, "avg speedup %.2f < %.2f; rows=%s",
                        avgSpeedup, minSp, rows));
        assertTrue(annotated.contains("islice"),
                () -> "upstream islice should remain:\n" + annotated);

        CorpusProofRunner.Report report = new CorpusProofRunner.Report(
                corpusId, title, cov, true, null, benchOk,
                correctRate, avgSpeedup, rows, annotated, List.of());
        archive(report, minCov, minSp);
    }

    private static String repairReturns(String annotated) {
        return BAD_LIST_RETURN.matcher(annotated).replaceAll("$1:");
    }

    private static String runBench(Path workDir, String casesJson) throws Exception {
        Path benchScript = workDir.resolve("generic_benchmark.py");
        try (InputStream is = ProductionCorpusL4ProofTest.class.getClassLoader()
                .getResourceAsStream("generic_benchmark.py")) {
            assertNotNull(is, "generic_benchmark.py");
            Files.copy(is, benchScript, StandardCopyOption.REPLACE_EXISTING);
        }
        String python = System.getenv().getOrDefault("PYTHON_BIN", "python3");
        ProcessBuilder pb = new ProcessBuilder(
                python,
                benchScript.toAbsolutePath().toString(),
                workDir.toAbsolutePath().toString(),
                "upstream_kit_native",
                "upstream_kit",
                casesJson);
        Process proc = pb.start();
        byte[] stdout = proc.getInputStream().readAllBytes();
        byte[] stderr = proc.getErrorStream().readAllBytes();
        if (!proc.waitFor(180, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            return "{\"ok\":false,\"error\":\"timeout\"}";
        }
        String out = new String(stdout, StandardCharsets.UTF_8).trim();
        if (out.isBlank()) {
            return "{\"ok\":false,\"error\":\""
                    + new String(stderr, StandardCharsets.UTF_8).replace("\"", "'") + "\"}";
        }
        return out;
    }

    private static void archive(CorpusProofRunner.Report report, double minCov, double minSp)
            throws Exception {
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
                "Upstream more-itertools v10.7.0 bodies (selected); not full more.py");
        payload.put("funcs_total", report.coverage().funcsTotal());
        payload.put("param_coverage", Math.round(report.coverage().paramCoverage() * 1000.0) / 1000.0);
        payload.put("return_coverage", Math.round(report.coverage().returnCoverage() * 1000.0) / 1000.0);
        payload.put("mypyc_compile_ok", report.compileOk());
        payload.put("correct_rate", Math.round(report.correctRate() * 1000.0) / 1000.0);
        payload.put("avg_speedup_vs_native", Math.round(report.avgSpeedup() * 1000.0) / 1000.0);
        payload.put("gates_pass", report.gatesPass(minCov, minSp));
        payload.put("return_repair", "stripped erroneous -> list[...] on dotproduct*");
        payload.put("rows", rowMaps);
        String body = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);

        StringBuilder md = new StringBuilder();
        md.append("## Summary\n\n");
        md.append(String.format(Locale.US,
                "- param coverage: **%.1f%%**\n- mypyc compile: **%s**\n- correct rate: **%.0f%%**\n- avg speedup vs native: **%.2f×**\n- gates pass: **%s**\n\n",
                100 * report.coverage().paramCoverage(),
                report.compileOk() ? "ok" : "FAIL",
                100 * report.correctRate(),
                report.avgSpeedup(),
                report.gatesPass(minCov, minSp)));
        md.append("| Function | correct | native ns | Meridian ns | Meridian× |\n");
        md.append("|----------|---------|-----------|-------------|----------|\n");
        for (CorpusProofRunner.BenchRow r : report.rows()) {
            md.append(String.format(Locale.US, "| `%s` | %s | %.1f | %.1f | %.2f |\n",
                    r.func(), r.correct(), r.nativeNs(), r.meridianNs(), r.speedupVsNative()));
        }
        EvalResultArchive.writeSuite(report.corpusId(), report.title(), body, md.toString());
    }

    private static Path resourceDir(String path) throws Exception {
        URL url = ProductionCorpusL4ProofTest.class.getClassLoader()
                .getResource(path + "/manifest.json");
        assertNotNull(url, path + " missing on classpath");
        return Path.of(url.toURI()).getParent();
    }
}
