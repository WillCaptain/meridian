package org.twelve.meridian.python.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeridianCliTest {

    @TempDir Path tmp;

    @Test
    void versionPrints() {
        int code = MeridianCli.run(new String[]{"version"});
        assertEquals(0, code);
    }

    @Test
    void helpPrints() {
        int code = MeridianCli.run(new String[]{"help"});
        assertEquals(0, code);
    }

    @Test
    void inferAnnotatesSimpleFile() throws Exception {
        Path py = tmp.resolve("add.py");
        Files.writeString(py, "def add(a, b):\n    return a + b\n\nx = add(1, 2)\n");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream old = System.out;
        System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
        try {
            int code = MeridianCli.run(new String[]{"infer", py.toString()});
            assertEquals(0, code);
        } finally {
            System.setOut(old);
        }
        String out = buf.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("def add"), out);
        // Best-effort: inference should attach at least one int annotation at call site.
        assertTrue(out.contains("int") || out.contains("->"),
                "expected some type annotation, got:\n" + out);
    }

    @Test
    void compile_specializes_multi_concrete_call_sites() throws Exception {
        Path py = tmp.resolve("poly_cli.py");
        Files.writeString(py, "def f(x):\n    return x + x\n");
        Path outDir = tmp.resolve("poly_cli_out");

        // Avoid System.out capture — parallel tests share the JVM streams.
        int code = MeridianCli.run(new String[]{
                "compile", py.toString(),
                "-o", outDir.toString(),
                "--calls-inline", "f(1)\nf(\"z\")\n",
                "--annotate-all"
        });
        assertEquals(0, code);
        String annotated = Files.readString(outDir.resolve("poly_cli.py"));
        assertTrue(annotated.contains("isinstance"), annotated);
        assertTrue(Files.list(outDir).anyMatch(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".so") || n.endsWith(".pyd");
                }),
                "mypyc native extension expected in " + outDir);
    }

    @Test
    void compile_bench_matches_native_eval() throws Exception {
        Path py = tmp.resolve("sumcli.py");
        Files.writeString(py, """
                def sum_range(n):
                    total = 0
                    for i in range(n):
                        total += i
                    return total
                """);
        Path outDir = tmp.resolve("sumcli_out");
        int code = MeridianCli.run(new String[]{
                "compile", py.toString(),
                "-o", outDir.toString(),
                "--calls-inline", "sum_range(200)\n",
                "--annotate-all",
                "--bench", "[[\"sum_range\",[800],12000]]"
        });
        assertEquals(0, code, "compile --bench must pass native correctness");
        assertTrue(Files.isRegularFile(outDir.resolve("sumcli_native.py")));
    }

    @Test
    void compile_mini_project_calls_file_prunes_and_specializes() throws Exception {
        Path dir = tmp.resolve("mini_proj");
        Files.createDirectories(dir);
        Path lib = dir.resolve("stats_kit.py");
        Path calls = dir.resolve("calls.py");
        Files.writeString(lib, loadClasspath("mini_project/stats_kit.py"));
        Files.writeString(calls, loadClasspath("mini_project/calls.py"));
        Path outDir = tmp.resolve("mini_proj_out");

        int code = MeridianCli.run(new String[]{
                "compile", lib.toString(),
                "--calls", calls.toString(),
                "--annotate-all",
                "-o", outDir.toString(),
                "--bench", "[[\"rolling_sum\",[300],12000]]"
        });
        assertEquals(0, code, "meridian compile --calls mini_project must pass");
        String annotated = Files.readString(outDir.resolve("stats_kit.py"));
        assertFalse(annotated.contains("def unused_histogram"), annotated);
        assertFalse(annotated.contains("def unused_format_report"), annotated);
        assertTrue(annotated.contains("_tag_int") || annotated.contains("isinstance"),
                annotated);
    }

    private static String loadClasspath(String path) throws Exception {
        var url = MeridianCliTest.class.getClassLoader().getResource(path);
        assertTrue(url != null, path);
        return Files.readString(Path.of(url.toURI()));
    }

    @Test
    void compile_pkg_keep_deps_uses_import_closure() throws Exception {
        Path pkg = tmp.resolve("pkg");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("mi_hot.py"), """
                def add1(x):
                    return x + 1
                """);
        Files.writeString(pkg.resolve("mi_facade.py"), """
                from mi_hot import add1

                def hot(n):
                    total = 0
                    for i in range(n):
                        total += add1(i)
                    return total
                """);
        Files.writeString(pkg.resolve("mi_extra.py"), """
                def unused(x):
                    return x
                """);
        Path outDir = tmp.resolve("pkg_cli_out");
        int code = MeridianCli.run(new String[]{
                "compile",
                "--pkg", pkg.toString(),
                "--primary", "mi_facade",
                "--annotation-mode", "keep_deps",
                "--compile-imports",
                "--calls-inline", "from mi_facade import hot\nhot(20)\n",
                "-o", outDir.toString()
        });
        assertEquals(0, code, "meridian compile --pkg keep_deps must pass");
        assertTrue(Files.isRegularFile(outDir.resolve("mi_hot.py")));
        assertTrue(Files.isRegularFile(outDir.resolve("mi_extra.py")),
                "coverage-only still written");
        assertTrue(Files.list(outDir).anyMatch(p -> {
            String n = p.getFileName().toString();
            return n.endsWith(".so") || n.endsWith(".pyd");
        }));
    }

    @Test
    void compile_prunes_unused_and_benches_hot_path() throws Exception {
        Path py = tmp.resolve("prune_cli.py");
        Files.writeString(py, """
                def hot(n):
                    total = 0
                    for i in range(n):
                        total += i
                    return total

                def unused(n):
                    return n + 1
                """);
        Path outDir = tmp.resolve("prune_cli_out");
        int code = MeridianCli.run(new String[]{
                "compile", py.toString(),
                "-o", outDir.toString(),
                "--calls-inline", "hot(100)\n",
                "--annotate-all",
                "--bench", "[[\"hot\",[600],15000]]"
        });
        assertEquals(0, code, "compile --bench with prune must pass");
        String annotated = Files.readString(outDir.resolve("prune_cli.py"));
        assertFalse(annotated.contains("def unused"), annotated);
        assertTrue(annotated.contains("def hot"), annotated);
    }
}
