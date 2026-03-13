package org.twelve.meridian.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.twelve.gcp.ast.AST;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Produces data for CGO §2.2 Table 1 and §5.3 Table 3.
 *
 * <p>Four paths measured for math_utils_bare.py:
 * <pre>
 *   CPython(bare)     — interpreted .py, zero annotations
 *   mypyc(bare)       — compiled zero-annotation .py
 *   mypyc(GCP demand) — GCP demand-driven inference + mypyc
 *   mypyc(manual)     — developer-written full annotations (oracle ceiling)
 * </pre>
 *
 * Also measures GCP inference overhead (todo 2): how many milliseconds
 * GCP-Python itself takes to analyse the source, independent of mypyc.
 */
class Table1BenchmarkTest {

    @Test
    void produce_table1_table3_with_manual_oracle() throws Exception {
        banner("CGO Table 1 + Table 3 — four-way + GCP inference overhead");

        // ── load sources ──────────────────────────────────────────────────────
        var bareUrl   = getClass().getClassLoader().getResource("math_utils_bare.py");
        var callsUrl  = getClass().getClassLoader().getResource("math_utils_calls.py");
        var manualUrl = getClass().getClassLoader().getResource("math_utils_manual.py");
        assertNotNull(bareUrl,   "math_utils_bare.py not found");
        assertNotNull(callsUrl,  "math_utils_calls.py not found");
        assertNotNull(manualUrl, "math_utils_manual.py not found");

        String bareSrc   = Files.readString(Path.of(bareUrl.toURI()),   StandardCharsets.UTF_8);
        String callsSrc  = Files.readString(Path.of(callsUrl.toURI()),  StandardCharsets.UTF_8);
        String manualSrc = Files.readString(Path.of(manualUrl.toURI()), StandardCharsets.UTF_8);

        // ── TODO 2: measure GCP inference overhead ────────────────────────────
        section("TODO 2 — GCP inference overhead (wall-clock ms)");
        // Warm up JVM first (discard)
        for (int w = 0; w < 3; w++) {
            PythonInferencer warmup = new PythonInferencer();
            warmup.inferWithContext(bareSrc, callsSrc);
        }
        // Measure 10 runs
        long[] inferMs = new long[10];
        AST[] finalAsts = null;
        for (int r = 0; r < 10; r++) {
            long t0 = System.nanoTime();
            PythonInferencer inf = new PythonInferencer();
            AST[] asts = inf.inferWithContext(bareSrc, callsSrc);
            inferMs[r] = (System.nanoTime() - t0) / 1_000_000;
            if (r == 9) finalAsts = asts;
        }
        Arrays.sort(inferMs);
        long inferMedianMs = inferMs[5];
        long inferMinMs    = inferMs[0];
        long inferMaxMs    = inferMs[9];
        System.out.printf("  GCP inference (inferWithContext):  median %d ms,  min %d ms,  max %d ms%n",
                inferMedianMs, inferMinMs, inferMaxMs);

        // ── TODO 2: annotation writer overhead ───────────────────────────────
        long[] writeMs = new long[10];
        String annotated = null;
        for (int r = 0; r < 10; r++) {
            long t0 = System.nanoTime();
            annotated = new PythonAnnotationWriter().annotate(bareSrc, finalAsts[0], finalAsts[1]);
            writeMs[r] = (System.nanoTime() - t0) / 1_000_000;
        }
        Arrays.sort(writeMs);
        long writeMedianMs = writeMs[5];
        System.out.printf("  PythonAnnotationWriter.annotate(): median %d ms%n", writeMedianMs);
        System.out.printf("  Total GCP-Python pipeline overhead: ~%d ms%n", inferMedianMs + writeMedianMs);
        System.out.println();
        System.out.println("  ► For the paper: GCP-Python adds " + (inferMedianMs + writeMedianMs) +
                " ms of analysis overhead for the evaluated benchmarks.");
        System.out.println("    This is amortized once per library version; mypyc compilation itself");
        System.out.println("    (seconds) dominates the total build time.");

        assertTrue(annotated != null && annotated.contains("n: int"),
                "GCP must annotate n: int");

        // ── compile all three mypyc variants in parallel ──────────────────────
        section("Step 3 — mypyc compile bare + GCP + manual (parallel)");
        Path workDir = Files.createTempDirectory("meridian_table1_");

        String bareModName   = "math_utils_bare";
        String gcpModName    = "math_utils_gcp";
        String manualModName = "math_utils_manual";

        Path barePath   = workDir.resolve(bareModName   + ".py");
        Path gcpPath    = workDir.resolve(gcpModName    + ".py");
        Path manualPath = workDir.resolve(manualModName + ".py");
        Files.writeString(barePath,   bareSrc,   StandardCharsets.UTF_8);
        Files.writeString(gcpPath,    annotated, StandardCharsets.UTF_8);
        Files.writeString(manualPath, manualSrc, StandardCharsets.UTF_8);

        MypycRunner runner = new MypycRunner();
        Path bareDir   = workDir.resolve("_bare");
        Path gcpDir    = workDir.resolve("_gcp");
        Path manualDir = workDir.resolve("_manual");
        Files.createDirectories(bareDir);
        Files.createDirectories(gcpDir);
        Files.createDirectories(manualDir);
        Files.copy(barePath,   bareDir.resolve(barePath.getFileName()),     StandardCopyOption.REPLACE_EXISTING);
        Files.copy(gcpPath,    gcpDir.resolve(gcpPath.getFileName()),       StandardCopyOption.REPLACE_EXISTING);
        Files.copy(manualPath, manualDir.resolve(manualPath.getFileName()), StandardCopyOption.REPLACE_EXISTING);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        Future<MypycRunner.CompileResult> bareFut   =
                pool.submit(() -> runner.compile(bareDir.resolve(barePath.getFileName()).toFile(),     bareDir.toFile()));
        Future<MypycRunner.CompileResult> gcpFut    =
                pool.submit(() -> runner.compile(gcpDir.resolve(gcpPath.getFileName()).toFile(),       gcpDir.toFile()));
        Future<MypycRunner.CompileResult> manualFut =
                pool.submit(() -> runner.compile(manualDir.resolve(manualPath.getFileName()).toFile(), manualDir.toFile()));
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.MINUTES);

        MypycRunner.CompileResult bareRes   = bareFut.get();
        MypycRunner.CompileResult gcpRes    = gcpFut.get();
        MypycRunner.CompileResult manualRes = manualFut.get();
        assertTrue(bareRes.success(),   "bare mypyc compile failed:\n"   + bareRes.stderr());
        assertTrue(gcpRes.success(),    "GCP  mypyc compile failed:\n"   + gcpRes.stderr());
        assertTrue(manualRes.success(), "manual mypyc compile failed:\n" + manualRes.stderr());

        Files.copy(bareRes.outputFile().toPath(),
                workDir.resolve(bareRes.outputFile().getName()), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(gcpRes.outputFile().toPath(),
                workDir.resolve(gcpRes.outputFile().getName()), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(manualRes.outputFile().toPath(),
                workDir.resolve(manualRes.outputFile().getName()), StandardCopyOption.REPLACE_EXISTING);

        System.out.println("  ✓ bare:   " + bareRes.outputFile().getName());
        System.out.println("  ✓ GCP:    " + gcpRes.outputFile().getName());
        System.out.println("  ✓ manual: " + manualRes.outputFile().getName());

        // Allow CPU to cool after parallel compilation (prevents thermal throttling
        // from contaminating benchmark timing on Apple Silicon)
        section("Step 3.5 — CPU cooldown (10 s)");
        Thread.sleep(10_000);

        // ── benchmark cases ───────────────────────────────────────────────────
        List<BenchCase> cases = List.of(
                new BenchCase("factorial",   List.of(10),        1_000_000),
                new BenchCase("factorial",   List.of(20),          500_000),
                new BenchCase("fibonacci",   List.of(30),          500_000),
                new BenchCase("sum_squares", List.of(100),         200_000),
                new BenchCase("sum_squares", List.of(1_000),        20_000),
                new BenchCase("is_prime",    List.of(997),          500_000),
                new BenchCase("is_prime",    List.of(9_999_991),      5_000)
        );

        // ── run GCP vs bare (for Table 1 GCP / bare columns) ─────────────────
        section("Step 4 — benchmark: bare vs GCP");
        String casesJson = buildCasesJson(cases);
        List<BenchRow> gcpRows = runBenchmark(workDir, bareModName, gcpModName, casesJson);

        // ── run manual vs bare (for Table 1 manual column) ───────────────────
        section("Step 5 — benchmark: bare vs manual (oracle)");
        List<BenchRow> manualRows = runBenchmark(workDir, bareModName, manualModName, casesJson);

        assertFalse(gcpRows.isEmpty(),    "GCP benchmark produced no rows");
        assertFalse(manualRows.isEmpty(), "Manual benchmark produced no rows");

        for (BenchRow r : gcpRows)    assertTrue(r.correct(), "GCP correctness failure: " + r.func());
        for (BenchRow r : manualRows) assertTrue(r.correct(), "Manual correctness failure: " + r.func());

        // ── print Table 1 (four-way) ──────────────────────────────────────────
        printTable1FourWay(gcpRows, manualRows);

        // ── print CV stability table ──────────────────────────────────────────
        printCVTable(gcpRows, manualRows);

        // ── print Table 3 demand× vs specializer× ────────────────────────────
        printTable3(gcpRows, manualRows);

        // ── print inference overhead summary for paper ────────────────────────
        printInferenceOverheadSummary(inferMedianMs, writeMedianMs);

        // ── assert GCP efficiency vs manual ──────────────────────────────────
        Map<String, Double> gcpMap    = indexByFunc(gcpRows);
        Map<String, Double> manualMap = indexByFunc(manualRows);
        double totalEfficiency = 0;
        int cnt = 0;
        for (String func : gcpMap.keySet()) {
            if (manualMap.containsKey(func) && manualMap.get(func) > 0) {
                double eff = gcpMap.get(func) / manualMap.get(func);
                totalEfficiency += eff;
                cnt++;
            }
        }
        double avgEfficiency = totalEfficiency / cnt;
        System.out.printf("%n  GCP efficiency vs manual: %.1f%%%n", avgEfficiency * 100);
        System.out.println("  (ideal: 100% = GCP matches human annotation; >80% = production-grade)");

        // GCP must be at least 70% as efficient as manual
        assertTrue(avgEfficiency >= 0.70,
                String.format("GCP efficiency vs manual must be ≥ 70%%; got %.1f%%", avgEfficiency * 100));
    }

    // ── output ────────────────────────────────────────────────────────────────

    private static void printTable1FourWay(List<BenchRow> gcpRows, List<BenchRow> manualRows) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║  CGO §2.2 Table 1 — Four-way (CPython / bare / GCP / manual oracle)                     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("  %-30s %12s %10s %10s %10s %10s %10s%n",
                "Function", "CPython(ns)", "bare×", "GCP×", "manual×", "GCP/man%", "gap");
        System.out.println("  " + "─".repeat(100));

        double sumBare = 0, sumGcp = 0, sumManual = 0, sumEff = 0;
        int cnt = 0;
        for (BenchRow r : gcpRows) {
            double manXv = 0;
            for (BenchRow mr : manualRows) {
                if (mr.func().equals(r.func())) { manXv = mr.speedupGcp(); break; }
            }
            double eff = manXv > 0 ? r.speedupGcp() / manXv * 100 : 0;
            double gap = manXv - r.speedupGcp();
            System.out.printf("  %-30s %12.1f %9.2f× %9.2f× %9.2f× %9.1f%% %9.2f×%n",
                    r.func(), r.cpythonNs(),
                    r.speedupBare(), r.speedupGcp(), manXv,
                    eff, gap);
            sumBare   += r.speedupBare();
            sumGcp    += r.speedupGcp();
            sumManual += manXv;
            if (manXv > 0) { sumEff += eff; cnt++; }
        }
        System.out.println("  " + "─".repeat(100));
        System.out.printf("  %-30s %12s %9.2f× %9.2f× %9.2f× %9.1f%%%n",
                "Average", "",
                sumBare / gcpRows.size(),
                sumGcp  / gcpRows.size(),
                sumManual / gcpRows.size(),
                cnt > 0 ? sumEff / cnt : 0);
        System.out.println();
        System.out.println("  Columns: bare× = mypyc(bare)/CPython, GCP× = mypyc(GCP)/CPython,");
        System.out.println("           manual× = mypyc(manual)/CPython, GCP/man% = how close GCP is to oracle.");
        System.out.println();
        System.out.println("  ► KEY FINDING: GCP achieves __% of manually-annotated performance automatically.");
        System.out.printf("    (Fill in: avg GCP/man = %.1f%%)%n", cnt > 0 ? sumEff / cnt : 0);
    }

    private static void printCVTable(List<BenchRow> gcpRows, List<BenchRow> manualRows) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  Measurement Stability — CV% of 5 timing samples (within-run)   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.printf("  %-30s %10s %10s %10s%n", "Function", "CV(CPython)", "CV(bare)", "CV(GCP)");
        System.out.println("  " + "─".repeat(65));
        double maxCv = 0;
        double sumCv = 0; int n = 0;
        for (BenchRow r : gcpRows) {
            System.out.printf("  %-30s %9.1f%% %9.1f%% %9.1f%%%n",
                    r.func(), r.cvCpythonPct(), r.cvBarePct(), r.cvGcpPct());
            maxCv = Math.max(maxCv, Math.max(r.cvCpythonPct(), Math.max(r.cvBarePct(), r.cvGcpPct())));
            sumCv += r.cvGcpPct(); n++;
        }
        System.out.println("  " + "─".repeat(65));
        System.out.printf("  Max CV across all configurations: %.1f%%%n", maxCv);
        System.out.printf("  Mean CV(GCP): %.1f%%%n", n > 0 ? sumCv / n : 0);
        System.out.println();
        System.out.println("  ► FOR PAPER §5.6: All within-run CVs < " + (int)(Math.ceil(maxCv)) + "%.");
        System.out.println("    (CGO reviewers: copy the max/mean values above into §5.6 text)");
    }

    private static void printTable3(List<BenchRow> gcpRows, List<BenchRow> manualRows) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║  CGO §5.3 Table 3 — demand× and manual× (oracle) comparison          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.printf("  %-30s %10s %10s %10s%n", "Function", "ret-only×", "demand×", "manual×");
        System.out.println("  " + "─".repeat(65));
        // ret-only× values from §5.3 (previously measured, kept for comparison)
        Map<String, Double> retOnly = Map.of(
                "factorial(10)",     1.66,
                "factorial(20)",     1.04,
                "fibonacci(30)",     2.02,
                "sum_squares(100)",  2.92,
                "sum_squares(1000)", 4.63,
                "is_prime(997)",     1.60,
                "is_prime(9999991)", 1.59
        );
        for (BenchRow r : gcpRows) {
            double manXv = 0;
            for (BenchRow mr : manualRows) {
                if (mr.func().equals(r.func())) { manXv = mr.speedupGcp(); break; }
            }
            double retX = retOnly.getOrDefault(r.func(), 0.0);
            System.out.printf("  %-30s %9.2f× %9.2f× %9.2f×%n",
                    r.func(), retX, r.speedupGcp(), manXv);
        }
    }

    private static void printInferenceOverheadSummary(long inferMs, long writeMs) {
        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  CGO TODO 2 — GCP-Python Inference Overhead                      ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.printf("  inferWithContext():          %3d ms (median over 10 runs, JVM warm)%n", inferMs);
        System.out.printf("  PythonAnnotationWriter:      %3d ms%n", writeMs);
        System.out.printf("  Total GCP-Python overhead:   %3d ms%n", inferMs + writeMs);
        System.out.println();
        System.out.println("  For the paper (§5.1 Experimental Setup):");
        System.out.printf("  \"GCP-Python analysis adds %d ms of preprocessing overhead.%n", inferMs + writeMs);
        System.out.println("   mypyc compilation (typically 2–10 s) dominates total build time.\"");
    }

    // ── benchmark runner ──────────────────────────────────────────────────────

    private List<BenchRow> runBenchmark(Path workDir, String bareMod, String annMod, String casesJson)
            throws Exception {
        Path benchScript = workDir.resolve("generic_benchmark_" + annMod + ".py");
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("generic_benchmark.py")) {
            assertNotNull(is);
            Files.copy(is, benchScript, StandardCopyOption.REPLACE_EXISTING);
        }
        String python = detectPython();
        Process proc = new ProcessBuilder(python,
                benchScript.toAbsolutePath().toString(),
                workDir.toAbsolutePath().toString(),
                bareMod, annMod, casesJson)
                .redirectErrorStream(false).start();
        byte[] stdout = proc.getInputStream().readAllBytes();
        byte[] stderr = proc.getErrorStream().readAllBytes();
        assertTrue(proc.waitFor(600, TimeUnit.SECONDS), "Benchmark timed out (" + annMod + ")");
        if (proc.exitValue() != 0)
            fail("Benchmark (" + annMod + ") failed:\n" + new String(stderr, StandardCharsets.UTF_8));

        JsonNode root = new ObjectMapper().readTree(stdout);
        if (root.has("error")) fail("Benchmark error: " + root.get("error").asText());
        List<BenchRow> rows = new ArrayList<>();
        for (JsonNode r : root.get("rows")) {
            if (r.has("error") && !r.has("cpython_ns")) {
                System.out.println("  [WARN] " + r.get("func").asText() + ": " + r.get("error").asText());
                continue;
            }
            rows.add(BenchRow.from(r));
        }
        return rows;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Map<String, Double> indexByFunc(List<BenchRow> rows) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (BenchRow r : rows) m.put(r.func(), r.speedupGcp());
        return m;
    }

    private static String buildCasesJson(List<BenchCase> cases) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < cases.size(); i++) {
            BenchCase c = cases.get(i);
            if (i > 0) sb.append(",");
            sb.append("[\"").append(c.func()).append("\",[");
            for (int j = 0; j < c.args().size(); j++) {
                if (j > 0) sb.append(",");
                sb.append(c.args().get(j));
            }
            sb.append("],").append(c.iters()).append("]");
        }
        return sb.append("]").toString();
    }

    private static String detectPython() {
        for (String cmd : new String[]{"python3", "python"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version").start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return "python3";
    }

    private static void banner(String title) {
        System.out.println("\n╔" + "═".repeat(title.length() + 4) + "╗");
        System.out.printf("║  %s  ║%n", title);
        System.out.println("╚" + "═".repeat(title.length() + 4) + "╝\n");
    }

    private static void section(String h) {
        System.out.println("\n── " + h + " " + "─".repeat(Math.max(0, 72 - h.length())));
    }

    // ── records ───────────────────────────────────────────────────────────────

    record BenchCase(String func, List<Object> args, int iters) {}

    record BenchRow(String func,
                    boolean correct,
                    double cpythonNs,
                    double mypycBareNs,
                    double mypycGcpNs,
                    double speedupBare,
                    double speedupGcp,
                    double cvCpythonPct,
                    double cvBarePct,
                    double cvGcpPct) {
        static BenchRow from(JsonNode n) {
            return new BenchRow(
                    n.get("func").asText(),
                    n.has("correct") && n.get("correct").asBoolean(false),
                    n.get("cpython_ns").asDouble(),
                    n.get("mypyc_bare_ns").asDouble(),
                    n.get("mypyc_gcp_ns").asDouble(),
                    n.get("speedup_bare").asDouble(),
                    n.get("speedup_gcp").asDouble(),
                    n.has("cv_cpython_pct") ? n.get("cv_cpython_pct").asDouble() : 0,
                    n.has("cv_bare_pct")    ? n.get("cv_bare_pct").asDouble()    : 0,
                    n.has("cv_gcp_pct")     ? n.get("cv_gcp_pct").asDouble()     : 0);
        }
        String funcBase() {
            int p = func.indexOf('('); return p >= 0 ? func.substring(0, p) : func;
        }
    }
}
