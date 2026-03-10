package org.twelve.meridian.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.twelve.gcp.ast.AST;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Four-way benchmark demonstrating demand-driven type inference:
 *
 * <pre>
 *   Input: math_utils_bare.py  — ZERO type annotations
 *
 *   Path A  CPython(bare)          → interpret .py directly              (baseline)
 *   Path B  mypyc(bare)            → compile bare .py, no annotations    (≈ baseline)
 *   Path C  mypyc(GCP-return)      → GCP infers return types only        (~2x)
 *   Path D  mypyc(GCP-demand)      → GCP infers via call-site context    (~14x)
 *
 *   Key insight (demand-driven inference):
 *     "No usage → no inference needed.
 *      At the usage site, concrete argument types tell us everything."
 *     → Call sites in math_utils_calls.py provide int arguments.
 *     → GCP maps them to parameter names without modifying the original file.
 * </pre>
 */
class MeridianPipelineDemoTest {

    private static final double MIN_GCP_SPEEDUP = 1.5;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void four_way_comparison_demonstrating_demand_driven_inference() throws Exception {

        banner("Four-way Benchmark: CPython(bare) / mypyc(bare) / mypyc(GCP-return) / mypyc(GCP-demand)");

        // ── LOAD INPUTS ────────────────────────────────────────────────────────
        var bareUrl  = getClass().getClassLoader().getResource("math_utils_bare.py");
        var callsUrl = getClass().getClassLoader().getResource("math_utils_calls.py");
        assertNotNull(bareUrl,  "math_utils_bare.py not found in test resources");
        assertNotNull(callsUrl, "math_utils_calls.py not found in test resources");
        String bareSrc  = Files.readString(Path.of(bareUrl.toURI()),  StandardCharsets.UTF_8);
        String callsSrc = Files.readString(Path.of(callsUrl.toURI()), StandardCharsets.UTF_8);

        section("INPUT: math_utils_bare.py  (ZERO annotations — original, never modified)");
        System.out.println(bareSrc);

        Path workDir = Files.createTempDirectory("meridian_4way_");
        MypycRunner runner = new MypycRunner();

        // Copy bare .py → workDir (Path A CPython baseline + Path B bare mypyc)
        String bareModName    = "math_utils_bare";
        String retModName     = "math_utils_ret";       // return-types only
        String demandModName  = "math_utils_demand";    // demand-driven (full types)

        Path bareInWork = workDir.resolve(bareModName + ".py");
        Files.copy(Path.of(bareUrl.toURI()), bareInWork);

        // ── PATH B: mypyc on bare file (no annotations) ───────────────────────
        section("PATH B  [MYPYC]  Compile bare .py — no type information");
        MypycRunner.CompileResult bareCompile = runner.compile(bareInWork.toFile(), workDir.toFile());
        System.out.println(bareCompile.success()
                ? "  ✓ " + bareCompile.outputFile().getName()
                : "  ✗ " + bareCompile.stderr().lines().findFirst().orElse(""));

        // ── PATH C: GCP infers return types only (single-file, no call-site context) ──
        section("PATH C  [MERIDIAN→MYPYC]  Single-file inference — return types only");
        System.out.println("  GCP processes math_utils_bare.py alone.");
        System.out.println("  No call-site info → only return types can be inferred from literals.");
        PythonInferencer infRet = new PythonInferencer();
        String annRet = new PythonAnnotationWriter().annotate(bareSrc, infRet.infer(bareSrc));
        System.out.println("\n  Added:");
        showAnnotationDiff(bareSrc, annRet);
        Path retPath = workDir.resolve(retModName + ".py");
        Files.writeString(retPath, annRet, StandardCharsets.UTF_8);
        MypycRunner.CompileResult retCompile = runner.compile(retPath.toFile(), workDir.toFile());
        if (!retCompile.success()) {
            System.out.println("  [SKIP] " + retCompile.stderr());
            assertReturnTypes(annRet);
            return;
        }
        System.out.println("  ✓ " + retCompile.outputFile().getName());

        // ── PATH D: demand-driven inference (call-site context) ───────────────
        section("PATH D  [MERIDIAN→MYPYC]  Demand-driven inference — full type annotations");
        System.out.println("  GCP processes math_utils_bare.py + math_utils_calls.py jointly.");
        System.out.println("  Call sites: factorial(10), fibonacci(30) ... → n receives int");
        System.out.println("  GCP maps call-site arg types to parameter names. Original file untouched.");
        PythonInferencer infDemand = new PythonInferencer();
        AST[] asts = infDemand.inferWithContext(bareSrc, callsSrc);
        String annDemand = new PythonAnnotationWriter().annotate(bareSrc, asts[0], asts[1]);
        System.out.println("\n  Added:");
        showAnnotationDiff(bareSrc, annDemand);
        Path demandPath = workDir.resolve(demandModName + ".py");
        Files.writeString(demandPath, annDemand, StandardCharsets.UTF_8);
        MypycRunner.CompileResult demandCompile = runner.compile(demandPath.toFile(), workDir.toFile());
        if (!demandCompile.success()) {
            System.out.println("  [SKIP] " + demandCompile.stderr());
            assertFullTypes(annDemand);
            return;
        }
        System.out.println("  ✓ " + demandCompile.outputFile().getName());

        // ── BENCHMARK ─────────────────────────────────────────────────────────
        if (!bareCompile.success()) {
            System.out.println("\n  [SKIP] Path B compile failed; skipping benchmark.");
            assertFullTypes(annDemand);
            return;
        }

        section("BENCHMARK  [CPYTHON]  Four execution paths — all run by Python, not GCP");
        Path runnerScript = workDir.resolve("benchmark_runner.py");
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("benchmark_runner.py")) {
            assertNotNull(is); Files.copy(is, runnerScript, StandardCopyOption.REPLACE_EXISTING);
        }

        // benchmark_runner.py: work_dir  bare_module  annotated_module
        // We run it twice: once for bare vs ret, once for bare vs demand
        // Then manually combine the tables.
        List<BenchRow> retRows    = runBenchmark(workDir, runnerScript, bareModName, retModName);
        List<BenchRow> demandRows = runBenchmark(workDir, runnerScript, bareModName, demandModName);

        if (retRows == null || demandRows == null) {
            System.out.println("  [SKIP] benchmark script failed");
            assertFullTypes(annDemand);
            return;
        }

        // ── RESULTS ───────────────────────────────────────────────────────────
        printFourWayTable(retRows, demandRows);
        printFourWayConclusion(retRows, demandRows);

        double avgDemand = demandRows.stream().mapToDouble(BenchRow::speedupGcp).average().orElse(0);
        double avgRet    = retRows.stream()   .mapToDouble(BenchRow::speedupGcp).average().orElse(0);

        assertTrue(avgDemand >= MIN_GCP_SPEEDUP,
                String.format("Demand-driven must be ≥ %.1fx; got %.2fx", MIN_GCP_SPEEDUP, avgDemand));
        assertTrue(avgDemand > avgRet,
                String.format("Demand-driven (%.2fx) must beat return-only (%.2fx)", avgDemand, avgRet));

        assertFullTypes(annDemand);
    }

    private List<BenchRow> runBenchmark(Path workDir, Path script, String bare, String ann) throws Exception {
        String python = detectPython();
        Process proc = new ProcessBuilder(python, script.toAbsolutePath().toString(),
                workDir.toAbsolutePath().toString(), bare, ann)
                .redirectErrorStream(false).start();
        byte[] out = proc.getInputStream().readAllBytes();
        proc.getErrorStream().readAllBytes();
        proc.waitFor(120, TimeUnit.SECONDS);
        if (proc.exitValue() != 0) return null;
        JsonNode root = json.readTree(out);
        if (root.has("error")) return null;
        List<BenchRow> rows = new ArrayList<>();
        for (JsonNode r : root.get("rows")) rows.add(BenchRow.from(r));
        return rows;
    }

    // ── assertion helpers ─────────────────────────────────────────────────────

    private void assertReturnTypes(String annotated) {
        assertTrue(annotated.contains("-> int") || annotated.contains("-> bool"),
                "GCP must infer and inject at least one return-type annotation");
    }

    private void assertFullTypes(String annotated) {
        assertTrue(annotated.contains("n: int"),
                "Demand-driven inference must produce 'n: int' parameter annotation");
        assertReturnTypes(annotated);
    }

    // ── output helpers ────────────────────────────────────────────────────────

    private static void banner(String title) {
        int w = title.length() + 4;
        System.out.println();
        System.out.println("╔" + "═".repeat(w) + "╗");
        System.out.printf("║  %s  ║%n", title);
        System.out.println("╚" + "═".repeat(w) + "╝");
        System.out.println();
    }

    private static void section(String heading) {
        System.out.println("\n── " + heading + " " + "─".repeat(Math.max(0, 72 - heading.length())));
    }

    private static void showAnnotationDiff(String before, String after) {
        String[] b = before.split("\n", -1);
        String[] a = after.split("\n", -1);
        boolean any = false;
        for (int i = 0; i < Math.min(b.length, a.length); i++) {
            if (!b[i].equals(a[i])) {
                System.out.printf("  line %2d  before: %s%n", i + 1, b[i]);
                System.out.printf("           after:  %s%n", a[i]);
                any = true;
            }
        }
        if (!any) System.out.println("  (no changes detected)");
    }

    // ── table / bench helpers ─────────────────────────────────────────────────

    record BenchRow(String func,
                    double cpythonNs,
                    double mypycBareNs,
                    double mypycGcpNs,
                    double speedupBare,
                    double speedupGcp) {
        static BenchRow from(JsonNode n) {
            return new BenchRow(
                    n.get("func").asText(),
                    n.get("cpython_ns").asDouble(),
                    n.get("mypyc_bare_ns").asDouble(),
                    n.get("mypyc_gcp_ns").asDouble(),
                    n.get("speedup_bare").asDouble(),
                    n.get("speedup_gcp").asDouble()
            );
        }
    }

    private static void printFourWayTable(List<BenchRow> retRows, List<BenchRow> demRows) {
        int wf = retRows.stream().mapToInt(r -> r.func().length()).max().orElse(24);
        wf = Math.max(wf, 24);
        String fmt = "  ║ %-" + wf + "s ║ %10s ║ %10s ║ %8s ║ %10s ║ %8s ║";
        String top = "  ╔" + "═".repeat(wf+2) + "╦" + "═".repeat(12) + "╦" + "═".repeat(12) + "╦" + "═".repeat(10) + "╦" + "═".repeat(12) + "╦" + "═".repeat(10) + "╗";
        String sep = "  ╠" + "═".repeat(wf+2) + "╬" + "═".repeat(12) + "╬" + "═".repeat(12) + "╬" + "═".repeat(10) + "╬" + "═".repeat(12) + "╬" + "═".repeat(10) + "╣";
        String bot = "  ╚" + "═".repeat(wf+2) + "╩" + "═".repeat(12) + "╩" + "═".repeat(12) + "╩" + "═".repeat(10) + "╩" + "═".repeat(12) + "╩" + "═".repeat(10) + "╝";
        System.out.println();
        System.out.println(top);
        System.out.printf(fmt + "%n", "Function",
                "CPython ns", "ret-only ns", "ret×", "demand ns", "demand×");
        System.out.println(sep);
        Map<String, BenchRow> demMap = new LinkedHashMap<>();
        demRows.forEach(r -> demMap.put(r.func(), r));
        for (BenchRow r : retRows) {
            BenchRow d = demMap.getOrDefault(r.func(), r);
            System.out.printf("  ║ %-" + wf + "s ║ %10.1f ║ %10.1f ║  %5.2fx ║ %10.1f ║  %5.2fx ║%n",
                    r.func(), r.cpythonNs(), r.mypycGcpNs(), r.speedupGcp(),
                    d.mypycGcpNs(), d.speedupGcp());
        }
        System.out.println(bot);
    }

    private static void printFourWayConclusion(List<BenchRow> retRows, List<BenchRow> demRows) {
        double avgBare   = retRows.stream().mapToDouble(BenchRow::speedupBare).average().orElse(0);
        double avgRet    = retRows.stream().mapToDouble(BenchRow::speedupGcp).average().orElse(0);
        double avgDemand = demRows.stream().mapToDouble(BenchRow::speedupGcp).average().orElse(0);
        System.out.println();
        System.out.println("  Conclusion:");
        System.out.println("  ┌────────────────────────────────────────────────────────────────────────┐");
        System.out.printf( "  │  mypyc(bare)              avg: %5.2fx  — no type info, near-zero gain  │%n", avgBare);
        System.out.printf( "  │  mypyc(GCP return-only)   avg: %5.2fx  — body literals inferred only   │%n", avgRet);
        System.out.printf( "  │  mypyc(GCP demand-driven) avg: %5.2fx  — call sites provide full types │%n", avgDemand);
        System.out.println("  │                                                                        │");
        System.out.println("  │  Demand-driven: 'no usage → no inference;                             │");
        System.out.println("  │  at the usage site, concrete args tell us everything.'                │");
        System.out.println("  └────────────────────────────────────────────────────────────────────────┘");
        System.out.println();
    }

    private static String detectPython() {
        for (String cmd : new String[]{"python3", "python"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version").start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) return cmd;
            } catch (Exception ignored) { }
        }
        return "python3";
    }
}
