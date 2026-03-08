package org.twelve.meridian.python;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bridges Java and CPython by spawning a Python subprocess that runs
 * {@code py_ast_dump.py} and returns the source file's AST as JSON.
 *
 * <p>The script is bundled as a classpath resource and extracted to a
 * temp file on first use so that the library works equally well from a
 * plain IDE classpath and from inside an executable JAR.
 */
public class PythonAstBridge {

    private static final int TIMEOUT_SECONDS = 30;

    private final ObjectMapper mapper = new ObjectMapper();
    private final String python;
    private final String scriptPath;

    public PythonAstBridge() {
        this.python = detectPython();
        this.scriptPath = extractScript();
    }

    // ── public API ────────────────────────────────────────────────────────────

    /**
     * Parse {@code pythonSource} and return the JSON AST as a plain Java map.
     *
     * @throws PythonParseException if the Python process reports a {@code SyntaxError}
     *                              or exits non-zero
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parse(String pythonSource) {
        try {
            ProcessBuilder pb = new ProcessBuilder(python, scriptPath, "-");
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            // Write source to stdin
            try (OutputStreamWriter w = new OutputStreamWriter(
                    proc.getOutputStream(), StandardCharsets.UTF_8)) {
                w.write(pythonSource);
            }

            // Read stdout (JSON) and stderr (error details) concurrently to avoid deadlock
            byte[] stdout = proc.getInputStream().readAllBytes();
            byte[] stderr = proc.getErrorStream().readAllBytes();

            boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new PythonParseException("Python process timed out after " + TIMEOUT_SECONDS + "s");
            }

            int exitCode = proc.exitValue();
            if (exitCode != 0) {
                String errMsg = new String(stderr, StandardCharsets.UTF_8).trim();
                throw new PythonParseException("Python exited " + exitCode + ": " + errMsg);
            }

            return mapper.readValue(stdout, Map.class);

        } catch (PythonParseException e) {
            throw e;
        } catch (Exception e) {
            throw new PythonParseException("Failed to invoke Python AST bridge", e);
        }
    }

    /**
     * Parse a Python source file by path.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> parseFile(File file) {
        try {
            ProcessBuilder pb = new ProcessBuilder(python, scriptPath, file.getAbsolutePath());
            pb.redirectErrorStream(false);
            Process proc = pb.start();

            byte[] stdout = proc.getInputStream().readAllBytes();
            byte[] stderr = proc.getErrorStream().readAllBytes();

            boolean finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                throw new PythonParseException("Python process timed out");
            }
            if (proc.exitValue() != 0) {
                throw new PythonParseException(new String(stderr, StandardCharsets.UTF_8).trim());
            }
            return mapper.readValue(stdout, Map.class);
        } catch (PythonParseException e) {
            throw e;
        } catch (Exception e) {
            throw new PythonParseException("Failed to parse " + file, e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String detectPython() {
        for (String cmd : new String[]{"python3", "python"}) {
            try {
                Process p = new ProcessBuilder(cmd, "--version").start();
                if (p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0) {
                    return cmd;
                }
            } catch (Exception ignored) { }
        }
        throw new IllegalStateException(
                "Python 3 not found on PATH. Install Python 3 or set PYTHON_BIN environment variable.");
    }

    private static String extractScript() {
        try (InputStream is = PythonAstBridge.class.getResourceAsStream("/py_ast_dump.py")) {
            if (is == null) {
                throw new IllegalStateException("py_ast_dump.py not found in classpath resources");
            }
            File tmp = File.createTempFile("meridian_py_ast_dump_", ".py");
            tmp.deleteOnExit();
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                is.transferTo(out);
            }
            return tmp.getAbsolutePath();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract py_ast_dump.py", e);
        }
    }

    // ── exception ─────────────────────────────────────────────────────────────

    public static class PythonParseException extends RuntimeException {
        public PythonParseException(String msg) { super(msg); }
        public PythonParseException(String msg, Throwable cause) { super(msg, cause); }
    }
}
