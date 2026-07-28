package org.twelve.meridian.python;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Prepare Meridian-annotated sources for mypyc: strip returns / deps / all
 * depending on {@link Mode}. Shared by {@link CompilePipeline} and corpus proofs.
 */
public final class MypycAnnotationPrep {

    /** Meridian sometimes over-widens scalar returns to {@code list[T]}; mypyc rejects those. */
    private static final Pattern BAD_LIST_RETURN = Pattern.compile(
            "(?m)^(def\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\([^)]*\\))\\s*->\\s*list\\[[^\\]]+\\]\\s*:");

    /**
     * How Meridian annotations are prepared for mypyc.
     * <ul>
     *   <li>{@code STRIP_DEPS} — keep param anns on primary; strip all on deps</li>
     *   <li>{@code KEEP} — keep param anns on every module; strip returns / AnnAssign</li>
     *   <li>{@code KEEP_DEPS} — L6: keep param anns on deps (hot); strip all on primary</li>
     *   <li>{@code STRIP_ALL} — erase every annotation before mypyc</li>
     * </ul>
     */
    public enum Mode {
        STRIP_DEPS,
        KEEP,
        KEEP_DEPS,
        STRIP_ALL;

        public static Mode parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return STRIP_DEPS;
            }
            return switch (raw.trim().toLowerCase(Locale.ROOT)) {
                case "keep" -> KEEP;
                case "keep_deps" -> KEEP_DEPS;
                case "strip_all" -> STRIP_ALL;
                case "strip_deps" -> STRIP_DEPS;
                default -> throw new IllegalArgumentException(
                        "Unknown annotation mode: " + raw
                                + " (use strip_deps|keep|keep_deps|strip_all)");
            };
        }

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private MypycAnnotationPrep() {}

    public static String repairReturns(String annotated) {
        if (annotated == null) {
            return null;
        }
        return BAD_LIST_RETURN.matcher(annotated).replaceAll("$1:");
    }

    /**
     * Apply mode for one module relative to {@code primary}.
     */
    public static String prepare(String module, String annotated, String primary, Mode mode) {
        String src = repairReturns(annotated);
        if (mode == null) {
            mode = Mode.STRIP_DEPS;
        }
        boolean isPrimary = module != null && module.equals(primary);
        return switch (mode) {
            case KEEP -> stripAnnotations(src, "returns");
            case KEEP_DEPS -> isPrimary
                    ? stripAnnotations(src, "all")
                    : stripAnnotations(src, "returns");
            case STRIP_ALL -> stripAnnotations(src, "all");
            case STRIP_DEPS -> isPrimary
                    ? stripAnnotations(src, "returns")
                    : stripAnnotations(src, "all");
        };
    }

    /**
     * AST-strip annotations via bundled {@code strip_annotations.py}.
     * {@code mode} is {@code returns} or {@code all}.
     */
    public static String stripAnnotations(String source, String mode) {
        if (source == null) {
            return null;
        }
        try {
            Path script = Files.createTempFile("strip_annotations", ".py");
            try (InputStream is = MypycAnnotationPrep.class.getClassLoader()
                    .getResourceAsStream("strip_annotations.py")) {
                if (is == null) {
                    return repairReturns(source);
                }
                Files.copy(is, script, StandardCopyOption.REPLACE_EXISTING);
            }
            ProcessBuilder pb = new ProcessBuilder(detectPython(),
                    script.toAbsolutePath().toString(), mode);
            pb.redirectErrorStream(false);
            Process proc = pb.start();
            proc.getOutputStream().write(source.getBytes(StandardCharsets.UTF_8));
            proc.getOutputStream().close();
            byte[] stdout = proc.getInputStream().readAllBytes();
            byte[] stderr = proc.getErrorStream().readAllBytes();
            if (!proc.waitFor(60, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return repairReturns(source);
            }
            if (proc.exitValue() != 0) {
                String err = new String(stderr, StandardCharsets.UTF_8);
                if (!err.isBlank()) {
                    System.err.println("strip_annotations failed: " + err);
                }
                return repairReturns(source);
            }
            String out = new String(stdout, StandardCharsets.UTF_8);
            return out.isBlank() ? repairReturns(source) : out;
        } catch (Exception e) {
            return repairReturns(source);
        }
    }

    private static String detectPython() {
        String env = System.getenv("PYTHON_BIN");
        if (env != null && !env.isBlank()) {
            return env;
        }
        for (String c : new String[]{
                "/opt/homebrew/bin/python3", "/usr/local/bin/python3", "python3"}) {
            if (c.equals("python3") || new java.io.File(c).exists()) {
                return c;
            }
        }
        return "python3";
    }
}
