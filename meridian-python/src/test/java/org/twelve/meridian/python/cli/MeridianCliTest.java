package org.twelve.meridian.python.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
