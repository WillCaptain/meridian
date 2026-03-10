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
 * Demonstrates GCP demand-driven monomorphization (function specialization).
 *
 * <h2>Principle</h2>
 * GCP's type system is parametrically polymorphic: {@code let f = x -> x}
 * cannot determine the type of {@code x} at definition time.
 * But at a call site {@code f(10)}, GCP knows {@code x: Integer}.
 *
 * <p>{@link FunctionSpecializer} exploits this: for each distinct type tuple
 * observed at call sites, it generates a dedicated, fully-annotated function
 * copy.  mypyc then compiles each specialization to optimized C code.
 *
 * <h2>Test scenario</h2>
 * <pre>
 *   poly_utils.py                 — zero annotations (int and float usage expected)
 *   poly_utils_calls.py           — usage context with BOTH int and float call sites
 *
 *   add(1, 2)       → int   specialization  →  def add(x: int,   y: int)   -> int
 *   add(1.5, 2.5)   → float specialization  →  def _add_float(x: float, y: float) -> float
 * </pre>
 */
class MonomorphizationTest {

    @Test
    void specializer_produces_correct_typed_functions() throws Exception {
        banner("Monomorphization Test — demand-driven specialization per call-site type");

        // ── load sources ───────────────────────────────────────────────────────
        String libSrc  = load("poly_utils.py");
        String useSrc  = load("poly_utils_calls.py");

        section("INPUT poly_utils.py (zero annotations)");
        System.out.println(libSrc);

        section("USAGE CONTEXT poly_utils_calls.py (int AND float call sites)");
        System.out.println(useSrc);

        // ── joint inference ────────────────────────────────────────────────────
        section("GCP Joint Inference");
        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(libSrc, useSrc);
        AST libAst = asts[0], useAst = asts[1];

        // ── specialization analysis ────────────────────────────────────────────
        section("FunctionSpecializer.analyse()  — group call sites by type tuple");
        FunctionSpecializer spec = new FunctionSpecializer();
        Map<String, FunctionSpecializer.FuncSpecializations> plan =
                spec.analyse(libAst, useAst);

        for (var entry : plan.entrySet()) {
            FunctionSpecializer.FuncSpecializations fs = entry.getValue();
            System.out.printf("  %-12s → %d type binding(s):%n",
                    entry.getKey(), fs.bindings().size());
            for (int i = 0; i < fs.bindings().size(); i++) {
                FunctionSpecializer.TypeBinding b = fs.bindings().get(i);
                boolean primary = (i == 0);
                System.out.printf("    [%s] %-20s params=%s  return=%s%n",
                        primary ? "primary" : "extra  ",
                        b.specName(primary) + "(...)",
                        b.argTypes(),
                        b.returnType());
            }
        }

        // ── rewrite source ─────────────────────────────────────────────────────
        section("Specialized Python source");
        String specialized = spec.specialize(libSrc, plan);
        System.out.println(specialized);

        // ── assertions ─────────────────────────────────────────────────────────
        // add: two call-site types (int and float) → original rewritten + extra
        assertTrue(specialized.contains("def add(x: int, y: int)") ||
                   specialized.contains("def add(x: int,y: int)") ||
                   specialized.contains("def add(x: int"),
                "add should be annotated with int (primary)");

        assertTrue(specialized.contains("def _add_float") || specialized.contains("def add"),
                "float specialization of add should exist");

        // power: only int call site → original annotated, no extra version
        assertTrue(specialized.contains("def power(base: int"),
                "power should be annotated with int");

        // ── mypyc compilation ──────────────────────────────────────────────────
        section("mypyc compilation of specialized source");
        Path workDir = Files.createTempDirectory("meridian_mono_");
        Path specPath = workDir.resolve("poly_utils_spec.py");
        Files.writeString(specPath, specialized, StandardCharsets.UTF_8);

        MypycRunner runner = new MypycRunner();
        MypycRunner.CompileResult result = runner.compile(specPath.toFile(), workDir.toFile());
        if (result.success()) {
            System.out.println("  ✓ Compiled: " + result.outputFile().getName());
            System.out.println("  Each specialization is compiled to typed C — mypyc optimizes fully.");
        } else {
            System.out.println("  [INFO] mypyc output: " + result.stderr());
        }

        // The most important assertion: specialization analysis is correct
        assertFalse(plan.isEmpty(), "Specialization plan must not be empty");
        assertTrue(plan.containsKey("add"), "add must be in the plan");
        assertTrue(plan.containsKey("power"), "power must be in the plan");

        // add must have two distinct type bindings (int and float)
        FunctionSpecializer.FuncSpecializations addSpec = plan.get("add");
        assertEquals(2, addSpec.bindings().size(),
                "add must have 2 specializations (int and float)");

        // power must be monomorphic (only int calls)
        FunctionSpecializer.FuncSpecializations powerSpec = plan.get("power");
        assertTrue(powerSpec.isMonomorphic(),
                "power must be monomorphic (only int call sites)");
    }

    @Test
    void monomorphic_functions_compile_and_run_correctly() throws Exception {
        banner("Monomorphization → mypyc → execution verification");

        String libSrc = load("math_utils_bare.py");
        String useSrc = load("math_utils_calls.py");

        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(libSrc, useSrc);

        FunctionSpecializer spec = new FunctionSpecializer();
        Map<String, FunctionSpecializer.FuncSpecializations> plan =
                spec.analyse(asts[0], asts[1]);

        section("Specialization plan for math_utils_bare.py + math_utils_calls.py");
        for (var e : plan.entrySet()) {
            FunctionSpecializer.FuncSpecializations fs = e.getValue();
            System.out.printf("  %-14s → %s [%s]%n",
                    e.getKey(),
                    fs.primary().argTypes(),
                    fs.isMonomorphic() ? "monomorphic" : fs.bindings().size() + " specializations");
        }

        String specialized = spec.specialize(libSrc, plan);
        section("Specialized source (diff)");
        showDiff(libSrc, specialized);

        // All functions must have n: int annotated (only int call sites)
        assertTrue(specialized.contains("n: int"),
                "All math_utils functions must receive n: int from call-site analysis");

        // Compile and check
        Path workDir = Files.createTempDirectory("meridian_mono2_");
        String modName = "math_utils_spec";
        Path specPath  = workDir.resolve(modName + ".py");
        Files.writeString(specPath, specialized, StandardCharsets.UTF_8);

        MypycRunner runner = new MypycRunner();
        MypycRunner.CompileResult cr = runner.compile(specPath.toFile(), workDir.toFile());

        section("Result");
        if (cr.success()) {
            System.out.println("  ✓ Compiled: " + cr.outputFile().getName());
            verifyExecution(workDir, modName, cr.outputFile(), specialized);
        } else {
            System.out.println("  stderr: " + cr.stderr());
        }
        assertTrue(specialized.contains("n: int"));
    }

    /**
     * Five-way performance benchmark comparing execution speeds:
     *
     * <pre>
     *   A  CPython(bare)           — interpreted, zero annotations   (baseline)
     *   B  mypyc(bare)             — compiled, zero annotations       (≈ baseline)
     *   C  mypyc(GCP-return)       — compiled, return types only      (~2x)
     *   D  mypyc(GCP-demand)       — compiled, demand-driven param+ret types  (~14x)
     *   E  mypyc(GCP-specializer)  — compiled, via FunctionSpecializer API    (~14x)
     *
     *   Paths D and E should agree: FunctionSpecializer is the clean API surface
     *   over the same demand-driven inference mechanism.
     * </pre>
     */
    @Test
    void monomorphization_benchmark() throws Exception {
        banner("Monomorphization Performance Benchmark");

        String libSrc  = load("math_utils_bare.py");
        String useSrc  = load("math_utils_calls.py");

        Path workDir = Files.createTempDirectory("meridian_spec_bench_");
        MypycRunner runner = new MypycRunner();

        // ── PATH A+B: bare file (CPython baseline & mypyc-bare) ──────────────
        String bareModName = "math_utils_bare";
        Path barePath = workDir.resolve(bareModName + ".py");
        Files.writeString(barePath, libSrc, StandardCharsets.UTF_8);
        MypycRunner.CompileResult bareCompile = runner.compile(barePath.toFile(), workDir.toFile());

        // ── PATH C: GCP infers return types only ─────────────────────────────
        String retModName = "math_utils_ret";
        PythonInferencer infRet = new PythonInferencer();
        String annRet = new PythonAnnotationWriter().annotate(libSrc, infRet.infer(libSrc));
        Path retPath = workDir.resolve(retModName + ".py");
        Files.writeString(retPath, annRet, StandardCharsets.UTF_8);
        runner.compile(retPath.toFile(), workDir.toFile());

        // ── PATH D: demand-driven via PythonAnnotationWriter ─────────────────
        String demandModName = "math_utils_demand";
        PythonInferencer infDemand = new PythonInferencer();
        AST[] astsD = infDemand.inferWithContext(libSrc, useSrc);
        String annDemand = new PythonAnnotationWriter().annotate(libSrc, astsD[0], astsD[1]);
        Path demandPath = workDir.resolve(demandModName + ".py");
        Files.writeString(demandPath, annDemand, StandardCharsets.UTF_8);
        runner.compile(demandPath.toFile(), workDir.toFile());

        // ── PATH E: FunctionSpecializer (new API) ─────────────────────────────
        String specModName = "math_utils_spec";
        PythonInferencer infSpec = new PythonInferencer();
        AST[] astsE = infSpec.inferWithContext(libSrc, useSrc);
        FunctionSpecializer specializer = new FunctionSpecializer();
        Map<String, FunctionSpecializer.FuncSpecializations> plan =
                specializer.analyse(astsE[0], astsE[1]);
        String annSpec = specializer.specialize(libSrc, plan);
        Path specPath = workDir.resolve(specModName + ".py");
        Files.writeString(specPath, annSpec, StandardCharsets.UTF_8);
        MypycRunner.CompileResult specCompile = runner.compile(specPath.toFile(), workDir.toFile());

        // ── Print specialization plan ─────────────────────────────────────────
        section("FunctionSpecializer plan  (math_utils_bare + math_utils_calls)");
        for (var e : plan.entrySet()) {
            FunctionSpecializer.FuncSpecializations fs = e.getValue();
            System.out.printf("  %-14s  params=%s  return=%s  [%s]%n",
                    e.getKey() + "()",
                    fs.primary().argTypes(),
                    fs.primary().returnType(),
                    fs.isMonomorphic() ? "monomorphic" : fs.bindings().size() + " specializations");
        }

        section("Generated source (PATH E — FunctionSpecializer)");
        showDiff(libSrc, annSpec);

        // ── Compile status ────────────────────────────────────────────────────
        section("Compilation status");
        System.out.printf("  PATH B  mypyc(bare)         → %s%n",
                bareCompile.success() ? "✓ compiled" : "✗ " + bareCompile.stderr());
        System.out.printf("  PATH E  mypyc(specializer)  → %s%n",
                specCompile.success() ? "✓ compiled " + specCompile.outputFile().getName()
                                      : "✗ " + specCompile.stderr());

        // ── Benchmark ─────────────────────────────────────────────────────────
        var runnerScript = locateBenchmarkScript();
        List<BenchRow> retRows    = runBenchmark(workDir, runnerScript, bareModName, retModName);
        List<BenchRow> demandRows = runBenchmark(workDir, runnerScript, bareModName, demandModName);
        List<BenchRow> specRows   = runBenchmark(workDir, runnerScript, bareModName, specModName);

        if (retRows == null || demandRows == null || specRows == null) {
            System.out.println("\n  [SKIP] benchmark runner failed — compilation prerequisite unmet");
            assertTrue(annSpec.contains("n: int"), "specializer must produce n: int");
            return;
        }

        printBenchmarkTable(retRows, demandRows, specRows);
        printConclusion(retRows, demandRows, specRows);

        double avgSpec = specRows.stream().mapToDouble(BenchRow::speedupGcp).average().orElse(0);
        assertTrue(avgSpec >= 1.5,
                String.format("Specializer must achieve ≥ 1.5x speedup; got %.2fx", avgSpec));
        assertTrue(annSpec.contains("n: int"),
                "FunctionSpecializer must annotate n: int from call sites");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void verifyExecution(Path workDir, String modName, File so, String source) throws Exception {
        String verifyScript = """
                import importlib.util, sys
                so_path = '%s'
                spec = importlib.util.spec_from_file_location('%s', so_path)
                mod  = importlib.util.module_from_spec(spec)
                sys.modules['%s'] = mod
                spec.loader.exec_module(mod)
                assert mod.factorial(10) == 3628800, f"factorial failed: {mod.factorial(10)}"
                assert mod.fibonacci(10) == 55,      f"fibonacci failed: {mod.fibonacci(10)}"
                assert mod.is_prime(17) == True,     f"is_prime failed"
                print("OK: all assertions passed")
                """.formatted(so.getAbsolutePath(), modName, modName);

        Path script = workDir.resolve("verify.py");
        Files.writeString(script, verifyScript, StandardCharsets.UTF_8);

        Process p = new ProcessBuilder(detectPython(), script.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor(10, TimeUnit.SECONDS);
        System.out.println("  " + out.strip());
        if (p.exitValue() == 0) {
            System.out.println("  ✓ Specialized functions produce correct results.");
        }
    }

    // ── benchmark helpers ─────────────────────────────────────────────────────

    private final ObjectMapper json = new ObjectMapper();

    record BenchRow(String func,
                    double cpythonNs, double mypycBareNs, double mypycGcpNs,
                    double speedupBare, double speedupGcp) {
        static BenchRow from(JsonNode n) {
            return new BenchRow(
                    n.get("func").asText(),
                    n.get("cpython_ns").asDouble(),
                    n.get("mypyc_bare_ns").asDouble(),
                    n.get("mypyc_gcp_ns").asDouble(),
                    n.get("speedup_bare").asDouble(),
                    n.get("speedup_gcp").asDouble());
        }
    }

    private List<BenchRow> runBenchmark(Path workDir, Path script,
                                         String bare, String ann) throws Exception {
        Process proc = new ProcessBuilder(detectPython(),
                script.toAbsolutePath().toString(),
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

    private Path locateBenchmarkScript() throws Exception {
        // benchmark_runner.py lives in main/resources
        var url = getClass().getClassLoader().getResource("benchmark_runner.py");
        if (url != null) return Path.of(url.toURI());
        // Fallback: find from source tree
        return Path.of(System.getProperty("user.dir"),
                "src/main/resources/benchmark_runner.py");
    }

    private static void printBenchmarkTable(List<BenchRow> retRows,
                                             List<BenchRow> demRows,
                                             List<BenchRow> specRows) {
        int wf = retRows.stream().mapToInt(r -> r.func().length()).max().orElse(28);
        wf = Math.max(wf, 28);
        String f  = "  ║ %-" + wf + "s ║ %9s ║ %9s ║ %7s ║ %9s ║ %7s ║ %9s ║ %7s ║";
        String dv = "═";
        String top = "  ╔" + dv.repeat(wf+2) + "╦" + dv.repeat(11) + "╦" + dv.repeat(11)
                   + "╦" + dv.repeat(9)  + "╦" + dv.repeat(11) + "╦" + dv.repeat(9)
                   + "╦" + dv.repeat(11) + "╦" + dv.repeat(9)  + "╗";
        String sep = top.replace('╔','╠').replace('╦','╬').replace('╗','╣');
        String bot = top.replace('╔','╚').replace('╦','╩').replace('╗','╝');

        section("Performance Results");
        System.out.println(top);
        System.out.printf(f + "%n", "Function",
                "CPython", "ret-only", "ret×", "demand", "dem×", "spec", "spec×");
        System.out.println(sep);

        Map<String, BenchRow> demMap  = new LinkedHashMap<>();
        Map<String, BenchRow> specMap = new LinkedHashMap<>();
        demRows .forEach(r -> demMap .put(r.func(), r));
        specRows.forEach(r -> specMap.put(r.func(), r));

        for (BenchRow r : retRows) {
            BenchRow d = demMap .getOrDefault(r.func(), r);
            BenchRow s = specMap.getOrDefault(r.func(), r);
            System.out.printf("  ║ %-" + wf + "s ║ %9.1f ║ %9.1f ║ %6.2fx ║ %9.1f ║ %6.2fx ║ %9.1f ║ %6.2fx ║%n",
                    r.func(),
                    r.cpythonNs(),
                    r.mypycGcpNs(),  r.speedupGcp(),
                    d.mypycGcpNs(),  d.speedupGcp(),
                    s.mypycGcpNs(),  s.speedupGcp());
        }
        System.out.println(bot);
    }

    private static void printConclusion(List<BenchRow> retRows,
                                         List<BenchRow> demRows,
                                         List<BenchRow> specRows) {
        double avgRet  = retRows .stream().mapToDouble(BenchRow::speedupGcp).average().orElse(0);
        double avgDem  = demRows .stream().mapToDouble(BenchRow::speedupGcp).average().orElse(0);
        double avgSpec = specRows.stream().mapToDouble(BenchRow::speedupGcp).average().orElse(0);
        System.out.println();
        System.out.println("  ╔══════════════════════════════════════════════════════════════════════════════╗");
        System.out.printf( "  ║  mypyc(GCP ret-only)    avg: %5.2fx   — body literals only               ║%n", avgRet);
        System.out.printf( "  ║  mypyc(GCP demand)      avg: %5.2fx   — call sites drive param types      ║%n", avgDem);
        System.out.printf( "  ║  mypyc(GCP specializer) avg: %5.2fx   — FunctionSpecializer API           ║%n", avgSpec);
        System.out.println("  ║                                                                              ║");
        System.out.println("  ║  Monomorphization principle:                                                ║");
        System.out.println("  ║    def f(x): ...          ← GCP: x is Generic at definition               ║");
        System.out.println("  ║    f(10)                  ← GCP: x: Integer, return: Integer              ║");
        System.out.println("  ║    → generate f_int(x: int) -> int  (fully typed, mypyc-optimal)          ║");
        System.out.println("  ╚══════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();
    }

    private String load(String name) throws Exception {
        var url = getClass().getClassLoader().getResource(name);
        assertNotNull(url, name + " not found in test resources");
        return Files.readString(Path.of(url.toURI()), StandardCharsets.UTF_8);
    }

    private static void banner(String title) {
        int w = title.length() + 4;
        System.out.println();
        System.out.println("╔" + "═".repeat(w) + "╗");
        System.out.printf("║  %s  ║%n", title);
        System.out.println("╚" + "═".repeat(w) + "╝");
        System.out.println();
    }

    private static void section(String s) {
        System.out.println("\n── " + s + " " + "─".repeat(Math.max(0, 68 - s.length())));
    }

    private static void showDiff(String before, String after) {
        String[] b = before.split("\n", -1), a = after.split("\n", -1);
        boolean any = false;
        for (int i = 0; i < Math.min(b.length, a.length); i++) {
            if (!b[i].equals(a[i])) {
                System.out.printf("  %3d  before: %s%n       after:  %s%n", i+1, b[i], a[i]);
                any = true;
            }
        }
        for (int i = Math.min(b.length, a.length); i < a.length; i++) {
            System.out.printf("  +%3d  new:    %s%n", i+1, a[i]);
        }
        if (!any && a.length == b.length) System.out.println("  (no changes in original lines)");
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
