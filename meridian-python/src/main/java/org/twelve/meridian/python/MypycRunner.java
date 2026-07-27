package org.twelve.meridian.python;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Invokes {@code mypyc} to compile a type-annotated Python file into a native
 * C extension ({@code .so} on macOS/Linux, {@code .pyd} on Windows).
 *
 * <h2>Workflow</h2>
 * <pre>
 *   Python source (unannotated)
 *       ↓ PythonInferencer + PythonAnnotationWriter
 *   Python source (annotated)
 *       ↓ MypycRunner.compile()
 *   .so / .pyd  (native extension, loadable by CPython)
 * </pre>
 *
 * <h2>Requirements</h2>
 * {@code mypyc} (shipped with mypy ≥ 0.900) must be on the system PATH.
 */
public class MypycRunner {

    private static final int TIMEOUT_SECONDS = 120;

    /** Result of a mypyc compilation run. */
    public record CompileResult(
            boolean success,
            File outputFile,       // null when success=false
            String stdout,
            String stderr,
            int exitCode
    ) {
        @Override
        public String toString() {
            return success
                    ? "CompileResult[ok, output=" + outputFile + "]"
                    : "CompileResult[FAILED exit=" + exitCode + ", stderr=" + stderr + "]";
        }
    }

    // ── public API ─────────────────────────────────────────────────────────────

    /**
     * Compile {@code sourceFile} with mypyc.
     * The output extension is placed in the same directory as the source.
     *
     * @throws IOException if the mypyc process cannot be started
     */
    public CompileResult compile(File sourceFile) throws IOException {
        return compile(sourceFile, sourceFile.getParentFile());
    }

    /**
     * Compile {@code sourceFile} with mypyc, placing output in {@code outputDir}.
     */
    public CompileResult compile(File sourceFile, File outputDir) throws IOException {
        return compile(List.of(sourceFile), outputDir, sourceFile);
    }

    /**
     * Compile one or more modules together (e.g. main + imported helpers) so mypyc
     * can generate native code for cross-module calls.
     *
     * @param primary the module whose {@code .so} is returned as {@link CompileResult#outputFile()}
     */
    public CompileResult compile(java.util.List<File> sourceFiles, File outputDir, File primary)
            throws IOException {
        if (sourceFiles == null || sourceFiles.isEmpty()) {
            throw new IllegalArgumentException("sourceFiles");
        }
        for (File sourceFile : sourceFiles) {
            if (!sourceFile.exists()) {
                throw new IllegalArgumentException("Source file not found: " + sourceFile);
            }
        }
        if (primary == null) primary = sourceFiles.getFirst();
        outputDir.mkdirs();

        String mypyc = detectMypyc();
        Path cacheDir = Files.createTempDirectory(outputDir.toPath(), ".mypy_cache_");

        java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
        cmd.add(mypyc);
        for (File sourceFile : sourceFiles) {
            cmd.add(sourceFile.getAbsolutePath());
        }
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(outputDir);
        pb.environment().put("MYPY_CACHE_DIR", cacheDir.toAbsolutePath().toString());
        pb.redirectErrorStream(false);
        Process proc = pb.start();

        byte[] stdout = proc.getInputStream().readAllBytes();
        byte[] stderr = proc.getErrorStream().readAllBytes();

        boolean finished;
        try {
            finished = proc.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for mypyc", e);
        }
        if (!finished) {
            proc.destroyForcibly();
            return new CompileResult(false, null,
                    new String(stdout, StandardCharsets.UTF_8),
                    "mypyc timed out after " + TIMEOUT_SECONDS + "s", -1);
        }

        int exitCode = proc.exitValue();
        String stdoutStr = new String(stdout, StandardCharsets.UTF_8);
        String stderrStr = new String(stderr, StandardCharsets.UTF_8);

        File outFile = findOutputFile(outputDir, baseNameOf(primary));
        if (outFile == null) {
            outFile = findOutputFile(primary.getParentFile(), baseNameOf(primary));
        }

        if (exitCode != 0) {
            return new CompileResult(false, null, stdoutStr, stderrStr, exitCode);
        }
        if (outFile == null) {
            return new CompileResult(false, null, stdoutStr,
                    stderrStr + "\n(mypyc exit 0 but no .so/.pyd found for "
                            + baseNameOf(primary) + " under " + outputDir + ")",
                    exitCode);
        }
        return new CompileResult(true, outFile, stdoutStr, stderrStr, exitCode);
    }

    // ── full pipeline shortcut ─────────────────────────────────────────────────

    /**
     * Run the full meridian-python pipeline on {@code sourceFile}:
     * <ol>
     *   <li>Infer types using GCP + semantic refine.</li>
     *   <li>Write annotated Python to a temp file.</li>
     *   <li>Compile the annotated file with mypyc.</li>
     * </ol>
     *
     * @return the mypyc compilation result
     */
    public CompileResult inferAndCompile(File sourceFile) throws IOException {
        String source = Files.readString(sourceFile.toPath(), StandardCharsets.UTF_8);

        PythonInferencer inferencer = new PythonInferencer();
        PythonInferencer.InferResult inferred = inferencer.inferFileDetailed(sourceFile);

        PythonAnnotationWriter writer = new PythonAnnotationWriter();
        Path tmpDir = Files.createTempDirectory("meridian_mypyc_");
        String baseName = baseNameOf(sourceFile);
        Path annotatedPath = tmpDir.resolve(baseName + "_annotated.py");
        String annotated = writer.annotate(source, inferred.inference());
        Files.writeString(annotatedPath, annotated, StandardCharsets.UTF_8);

        CompileResult result = compile(annotatedPath.toFile(), tmpDir.toFile());

        if (result.success() && result.outputFile() != null) {
            Path dest = sourceFile.toPath().resolveSibling(result.outputFile().getName());
            Files.copy(result.outputFile().toPath(), dest, StandardCopyOption.REPLACE_EXISTING);
            return new CompileResult(true, dest.toFile(),
                    result.stdout(), result.stderr(), result.exitCode());
        }
        return result;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static String detectMypyc() {
        String env = System.getenv("MYPYC_BIN");
        if (env != null && !env.isBlank()) return env;
        for (String candidate : new String[]{
                "/opt/homebrew/bin/mypyc",
                "/usr/local/bin/mypyc",
                "/usr/bin/mypyc",
                "mypyc"}) {
            if (candidate.equals("mypyc") || new File(candidate).exists()) return candidate;
        }
        return "mypyc";
    }

    private static String baseNameOf(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static File findOutputFile(File dir, String baseName) {
        if (dir == null || !dir.isDirectory()) return null;
        File[] direct = dir.listFiles((d, n) ->
                n.startsWith(baseName) && (n.endsWith(".so") || n.endsWith(".pyd")));
        if (direct != null && direct.length > 0) {
            return Arrays.stream(direct)
                    .max(Comparator.comparingLong(File::lastModified))
                    .orElse(null);
        }
        // mypyc sometimes leaves the artifact only under build/lib.*
        try (Stream<Path> walk = Files.walk(dir.toPath(), 4)) {
            return walk
                    .filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(f -> {
                        String n = f.getName();
                        return n.startsWith(baseName) && (n.endsWith(".so") || n.endsWith(".pyd"));
                    })
                    .max(Comparator.comparingLong(File::lastModified))
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
