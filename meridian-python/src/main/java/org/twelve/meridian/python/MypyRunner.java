package org.twelve.meridian.python;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Invokes {@code mypy} to type-check annotated Python (gate for annotate quality).
 */
public class MypyRunner {

    private static final int TIMEOUT_SECONDS = 120;

    public record CheckResult(boolean success, String stdout, String stderr, int exitCode) {}

    /**
     * Run {@code mypy --strict} on {@code sourceFile}.
     */
    public CheckResult checkStrict(File sourceFile) throws IOException {
        return check(sourceFile, true);
    }

    public CheckResult check(File sourceFile, boolean strict) throws IOException {
        if (!sourceFile.exists()) {
            throw new IllegalArgumentException("Source file not found: " + sourceFile);
        }
        String mypy = detectMypy();
        List<String> cmd = new ArrayList<>();
        cmd.add(mypy);
        if (strict) cmd.add("--strict");
        cmd.add("--no-error-summary");
        cmd.add(sourceFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(sourceFile.getParentFile());
        File cache = Files.createTempDirectory("meridian_mypy_cache_").toFile();
        pb.environment().put("MYPY_CACHE_DIR", cache.getAbsolutePath());
        Process proc = pb.start();
        byte[] stdout = proc.getInputStream().readAllBytes();
        byte[] stderr = proc.getErrorStream().readAllBytes();
        boolean finished;
        try {
            finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for mypy", e);
        }
        if (!finished) {
            proc.destroyForcibly();
            return new CheckResult(false,
                    new String(stdout, StandardCharsets.UTF_8),
                    "mypy timed out after " + TIMEOUT_SECONDS + "s", -1);
        }
        int exit = proc.exitValue();
        return new CheckResult(exit == 0,
                new String(stdout, StandardCharsets.UTF_8),
                new String(stderr, StandardCharsets.UTF_8),
                exit);
    }

    private static String detectMypy() {
        String env = System.getenv("MYPY_BIN");
        if (env != null && !env.isBlank()) return env;
        for (String candidate : new String[]{
                "/opt/homebrew/bin/mypy",
                "/usr/local/bin/mypy",
                "/usr/bin/mypy",
                "mypy"}) {
            if (candidate.equals("mypy") || new File(candidate).exists()) return candidate;
        }
        return "mypy";
    }
}
