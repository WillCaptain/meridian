package org.twelve.meridian.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end performance benchmark:
 *
 * <pre>
 *   math_utils.py  (fully annotated)
 *       ↓ MypycRunner.compile()
 *   math_utils.cpython-*.so
 *       ↓ benchmark_runner.py  (Python micro-benchmark)
 *   JSON timing report
 *       ↓ ASCII table + assertions
 * </pre>
 *
 * Expected outcome: mypyc-compiled code should be measurably faster than
 * the pure CPython interpreter for tight numeric loops.
 *
 * <p>The test is intentionally lenient: it requires only ≥ 1.5× speedup on
 * average, but typical results for well-annotated integer-loop code are
 * in the 3–10× range.
 */
class MypycBenchmarkTest {

    private static final double MIN_AVERAGE_SPEEDUP = 1.5;
    private final ObjectMapper json = new ObjectMapper();

    // ── benchmark record ──────────────────────────────────────────────────────

    record BenchRow(String func, double pyNs, double mypycNs, double speedup) {
        static BenchRow from(JsonNode node) {
            return new BenchRow(
                    node.get("func").asText(),
                    node.get("py_ns").asDouble(),
                    node.get("mypyc_ns").asDouble(),
                    node.get("speedup").asDouble()
            );
        }
    }

    // ── test ──────────────────────────────────────────────────────────────────

    @Test
    void mypyc_is_faster_than_pure_cpython() throws Exception {

        // ── 1. Compile math_utils.py with mypyc ──────────────────────────────
        var url = getClass().getClassLoader().getResource("math_utils.py");
        assertNotNull(url, "math_utils.py test resource not found");
        File srcFile = new File(url.toURI());

        Path workDir = Files.createTempDirectory("meridian_bench_");
        Path srcCopy = workDir.resolve("math_utils.py");
        Files.copy(srcFile.toPath(), srcCopy);

        MypycRunner runner = new MypycRunner();
        MypycRunner.CompileResult compile = runner.compile(srcCopy.toFile(), workDir.toFile());

        assertTrue(compile.success(),
                "mypyc compilation failed:\n" + compile.stderr());
        assertNotNull(compile.outputFile(), "No .so output");
        assertTrue(compile.outputFile().exists(), ".so file must exist");

        System.out.println("\n[meridian] mypyc compiled: " + compile.outputFile().getName());

        // ── 2. Extract benchmark_runner.py from classpath ────────────────────
        Path runnerScript = workDir.resolve("benchmark_runner.py");
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("benchmark_runner.py")) {
            assertNotNull(is, "benchmark_runner.py not in classpath resources");
            Files.copy(is, runnerScript, StandardCopyOption.REPLACE_EXISTING);
        }

        // ── 3. Run Python micro-benchmark ─────────────────────────────────────
        //   args: <py_dir>  <so_dir>  <module_name>
        //   py_dir = directory containing the original .py
        //   so_dir = directory containing the compiled .so
        String python = detectPython();
        ProcessBuilder pb = new ProcessBuilder(
                python,
                runnerScript.toAbsolutePath().toString(),
                srcFile.getParent(),          // py_dir  (original, uncopied .py)
                workDir.toAbsolutePath().toString(),  // so_dir
                "math_utils"
        );
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        byte[] stdout = proc.getInputStream().readAllBytes();
        byte[] stderr = proc.getErrorStream().readAllBytes();

        boolean done = proc.waitFor(120, TimeUnit.SECONDS);
        assertTrue(done, "Benchmark process timed out");

        int exit = proc.exitValue();
        if (exit != 0) {
            fail("Benchmark script exited " + exit + ":\n"
                    + new String(stderr, StandardCharsets.UTF_8));
        }

        // ── 4. Parse JSON results ─────────────────────────────────────────────
        JsonNode root = json.readTree(stdout);
        assertNotNull(root, "benchmark_runner.py produced no JSON output");

        if (root.has("error")) {
            fail("Benchmark script error: " + root.get("error").asText());
        }

        List<BenchRow> rows = new ArrayList<>();
        for (JsonNode row : root.get("rows")) {
            rows.add(BenchRow.from(row));
        }

        assertFalse(rows.isEmpty(), "Benchmark produced zero rows");

        // ── 5. Print results table ────────────────────────────────────────────
        printTable(rows);

        // ── 6. Assert performance ─────────────────────────────────────────────
        double totalSpeedup = rows.stream().mapToDouble(BenchRow::speedup).sum();
        double avgSpeedup   = totalSpeedup / rows.size();

        System.out.printf("%n  Average speedup across all benchmarks: %.2fx%n", avgSpeedup);
        System.out.printf("  (minimum required for test to pass: %.1fx)%n%n",
                MIN_AVERAGE_SPEEDUP);

        assertTrue(avgSpeedup >= MIN_AVERAGE_SPEEDUP,
                String.format("Expected average speedup ≥ %.1fx, got %.2fx",
                        MIN_AVERAGE_SPEEDUP, avgSpeedup));

        // Every individual benchmark must be at least marginally faster
        for (BenchRow row : rows) {
            assertTrue(row.speedup() >= 1.0,
                    String.format("mypyc must not be slower than CPython for %s (speedup=%.2f)",
                            row.func(), row.speedup()));
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static void printTable(List<BenchRow> rows) {
        int W_FUNC = rows.stream().mapToInt(r -> r.func().length()).max().orElse(20);
        W_FUNC = Math.max(W_FUNC, 24);

        String sep   = "╠" + "═".repeat(W_FUNC + 2) + "╬" + "═".repeat(12) + "╬" + "═".repeat(12) + "╬" + "═".repeat(11) + "╣";
        String top   = "╔" + "═".repeat(W_FUNC + 2) + "╦" + "═".repeat(12) + "╦" + "═".repeat(12) + "╦" + "═".repeat(11) + "╗";
        String bot   = "╚" + "═".repeat(W_FUNC + 2) + "╩" + "═".repeat(12) + "╩" + "═".repeat(12) + "╩" + "═".repeat(11) + "╝";
        String hdr   = String.format("║ %-" + W_FUNC + "s ║ %10s ║ %10s ║ %9s ║",
                "Function", "CPython ns", "mypyc ns", "Speedup");

        String titlePad = " ".repeat((W_FUNC + 2 + 12 + 12 + 11 + 3 * 3 - 44) / 2);
        String title = "║" + titlePad + "  Meridian Python Performance Benchmark  " + titlePad + " ║";

        System.out.println();
        System.out.println("╔" + "═".repeat(W_FUNC + 2 + 12 + 12 + 11 + 3 * 3) + "╗");
        System.out.println(title);
        System.out.println(top);
        System.out.println(hdr);
        System.out.println(sep);

        for (BenchRow r : rows) {
            System.out.printf("║ %-" + W_FUNC + "s ║ %10.1f ║ %10.1f ║  %6.2fx  ║%n",
                    r.func(), r.pyNs(), r.mypycNs(), r.speedup());
        }

        System.out.println(bot);
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
