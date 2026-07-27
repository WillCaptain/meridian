package org.twelve.meridian.python.cli;

import org.twelve.meridian.python.AnnotationPolicy;
import org.twelve.meridian.python.CompilePipeline;
import org.twelve.meridian.python.PythonAnnotationWriter;
import org.twelve.meridian.python.PythonInferencer;
import org.twelve.meridian.python.TypeAnnotationGenerator;
import org.twelve.meridian.python.TypeEvalPySiteExporter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Meridian product CLI.
 *
 * <pre>
 *   meridian infer   path.py [-o out.py] [--annotate-all]
 *   meridian stub    path.py [-o out.pyi]
 *   meridian sites   path.py [-o main_result.json]
 *   meridian compile path.py [-o out_dir] [--calls usage.py|--calls-inline CODE]
 *                    [--specialize|--no-specialize] [--annotate-all] [--bench cases.json]
 * </pre>
 *
 * <p>{@code infer}/{@code compile} default to {@link AnnotationPolicy#SAFE_PARTIAL}.
 * {@code compile} with usage context monomorphizes every distinct concrete
 * call-site type tuple (Outline optional/parametric bindings — not only str/int).
 */
public final class MeridianCli {

    private static final String VERSION = "0.1.0-SNAPSHOT";

    public static void main(String[] args) {
        int code = run(args);
        if (code != 0) {
            System.exit(code);
        }
    }

    /** Testable entry; does not call {@link System#exit}. */
    static int run(String[] args) {
        if (args == null || args.length == 0) {
            printHelp(System.out);
            return 2;
        }
        String cmd = args[0];
        if ("help".equals(cmd) || "-h".equals(cmd) || "--help".equals(cmd)) {
            printHelp(System.out);
            return 0;
        }
        if ("version".equals(cmd) || "-V".equals(cmd) || "--version".equals(cmd)) {
            System.out.println("meridian " + VERSION);
            return 0;
        }
        if (!List.of("infer", "stub", "sites", "compile").contains(cmd)) {
            System.err.println("Unknown command: " + cmd);
            printHelp(System.err);
            return 2;
        }

        List<String> rest = new ArrayList<>();
        Path out = null;
        boolean annotateAll = false;
        boolean specialize = true;
        boolean specializeExplicit = false;
        Path callsFile = null;
        String callsInline = null;
        Path benchFile = null;

        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("-o".equals(a) || "--output".equals(a)) {
                if (i + 1 >= args.length) {
                    System.err.println(a + " requires a path");
                    return 2;
                }
                out = Path.of(args[++i]);
            } else if ("--annotate-all".equals(a)) {
                annotateAll = true;
            } else if ("--specialize".equals(a)) {
                specialize = true;
                specializeExplicit = true;
            } else if ("--no-specialize".equals(a)) {
                specialize = false;
                specializeExplicit = true;
            } else if ("--calls".equals(a)) {
                if (i + 1 >= args.length) {
                    System.err.println("--calls requires a path");
                    return 2;
                }
                callsFile = Path.of(args[++i]);
            } else if ("--calls-inline".equals(a)) {
                if (i + 1 >= args.length) {
                    System.err.println("--calls-inline requires a code string");
                    return 2;
                }
                callsInline = args[++i];
            } else if ("--bench".equals(a)) {
                if (i + 1 >= args.length) {
                    System.err.println("--bench requires a cases JSON path or literal");
                    return 2;
                }
                benchFile = Path.of(args[++i]);
            } else if ("-h".equals(a) || "--help".equals(a)) {
                printHelp(System.out);
                return 0;
            } else if (a.startsWith("-")) {
                System.err.println("Unknown option: " + a);
                return 2;
            } else {
                rest.add(a);
            }
        }
        if (rest.size() != 1) {
            System.err.println("Usage: meridian " + cmd + " <file.py> [options]");
            return 2;
        }
        if (annotateAll && !"infer".equals(cmd) && !"compile".equals(cmd)) {
            System.err.println("--annotate-all is only valid with 'infer' or 'compile'");
            return 2;
        }
        if ((callsFile != null || callsInline != null || specializeExplicit || benchFile != null)
                && !"compile".equals(cmd)) {
            System.err.println("--calls/--specialize/--bench are only valid with 'compile'");
            return 2;
        }
        if (callsFile != null && callsInline != null) {
            System.err.println("Use only one of --calls or --calls-inline");
            return 2;
        }

        File input = new File(rest.get(0));
        if (!input.isFile()) {
            System.err.println("Not a file: " + input);
            return 1;
        }

        try {
            return switch (cmd) {
                case "stub" -> writeStub(input, out);
                case "sites" -> writeSites(input, out);
                case "compile" -> writeCompile(input, out, annotateAll, specialize,
                        callsFile, callsInline, benchFile);
                default -> writeAnnotated(input, out, annotateAll);
            };
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            System.err.println("Inference failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
    }

    private static int writeStub(File input, Path out) throws IOException {
        PythonInferencer.InferResult inferred = new PythonInferencer().inferFileDetailed(input);
        String stub = new TypeAnnotationGenerator().generate(inferred.inference());
        return emit(stub, out);
    }

    private static int writeAnnotated(File input, Path out, boolean annotateAll) throws IOException {
        String source = Files.readString(input.toPath(), StandardCharsets.UTF_8);
        PythonInferencer inferencer = new PythonInferencer();
        PythonInferencer.InferResult inferred = inferencer.inferFileDetailed(input);
        AnnotationPolicy policy = annotateAll
                ? AnnotationPolicy.ALL_CONCRETE
                : AnnotationPolicy.SAFE_PARTIAL;
        String annotated = new PythonAnnotationWriter()
                .withPolicy(policy)
                .annotate(source, inferred.inference());
        return emit(annotated, out);
    }

    private static int writeSites(File input, Path out) throws IOException {
        PythonInferencer.InferResult inferred = new PythonInferencer().inferFileDetailed(input);
        TypeEvalPySiteExporter exporter = new TypeEvalPySiteExporter();
        List<Map<String, Object>> sites = exporter.collect(inferred.inference());
        String json = exporter.toJson(sites);
        return emit(json, out);
    }

    private static int writeCompile(File input, Path out, boolean annotateAll, boolean specialize,
                                    Path callsFile, String callsInline, Path benchFile)
            throws IOException {
        String source = Files.readString(input.toPath(), StandardCharsets.UTF_8);
        String usage = null;
        if (callsFile != null) {
            if (!Files.isRegularFile(callsFile)) {
                System.err.println("Not a file: " + callsFile);
                return 1;
            }
            usage = Files.readString(callsFile, StandardCharsets.UTF_8);
        } else if (callsInline != null) {
            usage = callsInline;
        }

        String benchJson = null;
        if (benchFile != null) {
            if (Files.isRegularFile(benchFile)) {
                benchJson = Files.readString(benchFile, StandardCharsets.UTF_8).trim();
            } else {
                // Allow inline JSON literal as the "path" argument value.
                benchJson = benchFile.toString();
            }
        }

        Path outDir = out != null ? out : input.toPath().resolveSibling(
                baseName(input) + "_meridian_out");
        Files.createDirectories(outDir);

        AnnotationPolicy policy = annotateAll
                ? AnnotationPolicy.ALL_CONCRETE
                : AnnotationPolicy.SAFE_PARTIAL;

        // Specialize whenever usage is present unless user passed --no-specialize.
        boolean doSpecialize = specialize && usage != null && !usage.isBlank();

        CompilePipeline.Outcome outcome = new CompilePipeline().run(new CompilePipeline.Request(
                source,
                baseName(input),
                usage,
                doSpecialize,
                policy,
                outDir,
                benchJson
        ));

        System.err.println("Annotated: " + outcome.annotatedFile().toAbsolutePath());
        if (outcome.specialized()) {
            System.err.println("Specialized: yes (" + outcome.plan().size()
                    + " function(s); multi-concrete call-site tuples → clones + dispatcher)");
        }
        if (!outcome.compileResult().success()) {
            System.err.println("mypyc failed:");
            System.err.println(outcome.compileResult().stderr());
            // Still print annotated source to stdout for inspection.
            System.out.print(outcome.annotatedSource());
            if (!outcome.annotatedSource().endsWith("\n")) System.out.println();
            return 1;
        }
        System.err.println("Compiled:  " + outcome.compileResult().outputFile());
        if (outcome.benchJson() != null) {
            System.out.println(outcome.benchJson());
        } else {
            System.out.print(outcome.annotatedSource());
            if (!outcome.annotatedSource().endsWith("\n")) System.out.println();
        }
        return 0;
    }

    private static String baseName(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static int emit(String text, Path out) throws IOException {
        if (out == null) {
            System.out.print(text);
            if (!text.endsWith("\n")) {
                System.out.println();
            }
            return 0;
        }
        Path parent = out.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(out, text, StandardCharsets.UTF_8);
        System.err.println("Wrote " + out.toAbsolutePath());
        return 0;
    }

    private static void printHelp(java.io.PrintStream ps) {
        ps.println("Meridian — GCP Python type inferencer / mypyc compile");
        ps.println();
        ps.println("Usage:");
        ps.println("  meridian infer <file.py> [-o out.py] [--annotate-all]");
        ps.println("      Annotate source (PEP 484). Default SAFE_PARTIAL.");
        ps.println("  meridian stub  <file.py> [-o out.pyi]");
        ps.println("  meridian sites <file.py> [-o main_result.json]");
        ps.println("  meridian compile <file.py> [-o out_dir]");
        ps.println("      [--calls usage.py | --calls-inline CODE]");
        ps.println("      [--specialize | --no-specialize] [--annotate-all]");
        ps.println("      [--bench cases.json]");
        ps.println("      a) begin compile  b) annotate (+ specialize multi-concrete");
        ps.println("         Outline/GCP call-site bindings)  c) mypyc  d) optional bench");
        ps.println("  meridian version | help");
        ps.println();
        ps.println("Specialization is generic: every distinct concrete call-site type");
        ps.println("tuple (str/int/float/list/…) gets a clone + isinstance dispatcher.");
        ps.println("See meridian-python/docs/mypyc-compile-and-ide-plan.md");
    }

    private MeridianCli() {}
}
