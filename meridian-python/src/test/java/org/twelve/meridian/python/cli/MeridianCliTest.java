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
}
