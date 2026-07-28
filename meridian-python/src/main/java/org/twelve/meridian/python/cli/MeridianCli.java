package org.twelve.meridian.python.cli;

import org.twelve.meridian.python.AnnotationPolicy;
import org.twelve.meridian.python.CompilePipeline;
import org.twelve.meridian.python.HotCompileSelector;
import org.twelve.meridian.python.MypycAnnotationPrep;
import org.twelve.meridian.python.PythonAnnotationWriter;
import org.twelve.meridian.python.PythonInferencer;
import org.twelve.meridian.python.TypeAnnotationGenerator;
import org.twelve.meridian.python.TypeEvalPySiteExporter;
import org.twelve.meridian.python.eval.CorpusProofRunner;
import org.twelve.meridian.python.eval.EvalResultArchive;
import org.twelve.meridian.python.eval.PackageCorpusProofRunner;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Meridian product CLI.
 *
 * <pre>
 *   meridian infer   path.py [-o out.py] [--annotate-all]
 *   meridian stub    path.py [-o out.pyi]
 *   meridian sites   path.py [-o main_result.json]
 *   meridian compile path.py [-o out_dir] [--calls usage.py|--calls-inline CODE]
 *                    [--specialize|--no-specialize] [--annotate-all] [--bench cases.json]
 *   meridian compile --pkg dir/ --primary mod [-o out_dir]
 *                    [--calls …] [--annotation-mode keep_deps|strip_deps|keep|strip_all]
 *                    [--compile-modules a,b | --compile-imports]
 *   meridian corpus  corpus_dir/ [--archive]
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
        if ("corpus".equals(cmd)) {
            return runCorpus(args);
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
        Path pkgDir = null;
        String primaryModule = null;
        String annotationMode = null;
        List<String> compileModules = List.of();
        boolean compileImports = false;

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
            } else if ("--pkg".equals(a)) {
                if (i + 1 >= args.length) {
                    System.err.println("--pkg requires a directory");
                    return 2;
                }
                pkgDir = Path.of(args[++i]);
            } else if ("--primary".equals(a)) {
                if (i + 1 >= args.length) {
                    System.err.println("--primary requires a module name");
                    return 2;
                }
                primaryModule = args[++i];
            } else if ("--annotation-mode".equals(a)) {
                if (i + 1 >= args.length) {
                    System.err.println("--annotation-mode requires strip_deps|keep|keep_deps|strip_all");
                    return 2;
                }
                annotationMode = args[++i];
            } else if ("--compile-modules".equals(a)) {
                if (i + 1 >= args.length) {
                    System.err.println("--compile-modules requires comma-separated names");
                    return 2;
                }
                compileModules = Arrays.stream(args[++i].split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList());
            } else if ("--compile-imports".equals(a)) {
                compileImports = true;
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
        boolean packageCompile = "compile".equals(cmd) && pkgDir != null;
        if (packageCompile) {
            if (!rest.isEmpty()) {
                System.err.println("Do not pass <file.py> with --pkg");
                return 2;
            }
        } else if (rest.size() != 1) {
            System.err.println("Usage: meridian " + cmd + " <file.py> [options]");
            return 2;
        }
        if (annotateAll && !"infer".equals(cmd) && !"compile".equals(cmd)) {
            System.err.println("--annotate-all is only valid with 'infer' or 'compile'");
            return 2;
        }
        if ((callsFile != null || callsInline != null || specializeExplicit || benchFile != null
                || pkgDir != null || primaryModule != null || annotationMode != null
                || !compileModules.isEmpty() || compileImports)
                && !"compile".equals(cmd)) {
            System.err.println("compile-only options used with '" + cmd + "'");
            return 2;
        }
        if (callsFile != null && callsInline != null) {
            System.err.println("Use only one of --calls or --calls-inline");
            return 2;
        }
        if (compileImports && !compileModules.isEmpty()) {
            System.err.println("Use only one of --compile-modules or --compile-imports");
            return 2;
        }

        try {
            if (packageCompile) {
                return writePackageCompile(pkgDir, primaryModule, out, annotateAll,
                        callsFile, callsInline, annotationMode, compileModules, compileImports);
            }
            File input = new File(rest.get(0));
            if (!input.isFile()) {
                System.err.println("Not a file: " + input);
                return 1;
            }
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
        if (outcome.prunedFunctions() != null && !outcome.prunedFunctions().isEmpty()) {
            System.err.println("Pruned (unreachable from usage): "
                    + String.join(", ", outcome.prunedFunctions()));
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
            // 1) compile done  2) eval vs native  3) performance vs native
            System.err.println("Eval check: Meridian result vs native CPython");
            System.err.println("Perf check: Meridian mypyc vs native CPython");
            System.out.println(outcome.benchJson());
            if (!outcome.benchOk()) {
                System.err.println("FAIL: native eval correctness did not pass");
                return 1;
            }
            System.err.println("PASS: results match native; see speedup_vs_native in JSON");
            return 0;
        }
        System.out.print(outcome.annotatedSource());
        if (!outcome.annotatedSource().endsWith("\n")) System.out.println();
        return 0;
    }

    /**
     * Multi-module package compile: {@code --pkg} + {@code --primary} with
     * {@code keep_deps} / import-closure hot set (L6 product path).
     */
    private static int writePackageCompile(
            Path pkgDir,
            String primaryModule,
            Path out,
            boolean annotateAll,
            Path callsFile,
            String callsInline,
            String annotationMode,
            List<String> compileModules,
            boolean compileImports) throws IOException {
        if (pkgDir == null || !Files.isDirectory(pkgDir)) {
            System.err.println("Not a directory: " + pkgDir);
            return 1;
        }
        Map<String, String> modules = loadPackageModules(pkgDir);
        if (modules.isEmpty()) {
            System.err.println("No .py modules in " + pkgDir.toAbsolutePath());
            return 1;
        }
        if (primaryModule == null || primaryModule.isBlank()) {
            if (modules.size() == 1) {
                primaryModule = modules.keySet().iterator().next();
            } else {
                System.err.println("--primary is required for multi-module --pkg");
                return 2;
            }
        }
        if (!modules.containsKey(primaryModule)) {
            System.err.println("Primary module not found: " + primaryModule
                    + " (have " + String.join(", ", modules.keySet()) + ")");
            return 1;
        }

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

        String mode = annotationMode == null || annotationMode.isBlank()
                ? "keep_deps" : annotationMode;
        // Validate early for clear CLI errors.
        MypycAnnotationPrep.Mode.parse(mode);

        boolean useImportClosure = compileImports
                || (compileModules.isEmpty() && "keep_deps".equalsIgnoreCase(mode));
        List<String> compileSet = compileModules;
        if (useImportClosure) {
            compileSet = HotCompileSelector.importClosure(primaryModule, modules);
        }

        Path outDir = out != null ? out : pkgDir.resolve(primaryModule + "_meridian_out");
        Files.createDirectories(outDir);

        AnnotationPolicy policy = annotateAll
                ? AnnotationPolicy.ALL_CONCRETE
                : AnnotationPolicy.SAFE_PARTIAL;

        CompilePipeline.PackageOutcome outcome = new CompilePipeline().runPackage(
                new CompilePipeline.PackageRequest(
                        modules,
                        primaryModule,
                        usage,
                        compileSet,
                        false,
                        mode,
                        policy,
                        outDir
                ));

        System.err.println("Package:   " + pkgDir.toAbsolutePath());
        System.err.println("Primary:   " + primaryModule);
        System.err.println("Mode:      " + outcome.annotationMode().wireName());
        System.err.println("Compile:   " + String.join(", ", outcome.compileModules()));
        System.err.println("Annotated: " + outDir.toAbsolutePath());
        if (!outcome.compileResult().success()) {
            System.err.println("mypyc failed:");
            System.err.println(outcome.compileResult().stderr());
            System.out.print(outcome.mypycSources().getOrDefault(primaryModule, ""));
            return 1;
        }
        System.err.println("Compiled:  " + outcome.compileResult().outputFile());
        System.out.print(outcome.mypycSources().getOrDefault(primaryModule, ""));
        if (!outcome.mypycSources().getOrDefault(primaryModule, "").endsWith("\n")) {
            System.out.println();
        }
        return 0;
    }

    private static Map<String, String> loadPackageModules(Path pkgDir) throws IOException {
        Map<String, String> modules = new LinkedHashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pkgDir, "*.py")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                if (name.endsWith(".py")) {
                    String mod = name.substring(0, name.length() - 3);
                    modules.put(mod, Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        }
        return modules;
    }

    /**
     * Four-gate corpus proof on a directory with recipes.py / calls.py /
     * cases.json / manifest.json (optional calls_bench.py).
     */
    private static int runCorpus(String[] args) {
        Path dir = null;
        boolean archive = false;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("--archive".equals(a)) {
                archive = true;
            } else if ("-h".equals(a) || "--help".equals(a)) {
                System.out.println("Usage: meridian corpus <corpus_dir> [--archive]");
                System.out.println("Gates: coverage → mypyc → correctness → speedup vs native");
                return 0;
            } else if (a.startsWith("-")) {
                System.err.println("Unknown option: " + a);
                return 2;
            } else if (dir == null) {
                dir = Path.of(a);
            } else {
                System.err.println("Unexpected argument: " + a);
                return 2;
            }
        }
        if (dir == null || !Files.isDirectory(dir)) {
            System.err.println("Usage: meridian corpus <corpus_dir> [--archive]");
            return 2;
        }
        try {
            boolean isPackage = Files.isRegularFile(dir.resolve("manifest.json"))
                    && Files.readString(dir.resolve("manifest.json"), StandardCharsets.UTF_8)
                    .contains("\"package_dir\"");
            Path work = Files.createTempDirectory("meridian_corpus_cli_");
            final CorpusProofRunner.Report report;
            final double minCov;
            final double minSp;
            if (isPackage) {
                PackageCorpusProofRunner.PackageSpec spec = PackageCorpusProofRunner.loadDir(dir);
                report = new PackageCorpusProofRunner().evaluate(spec, work);
                minCov = spec.minParamCoverage();
                minSp = spec.minAvgSpeedup();
                if (archive) {
                    PackageCorpusProofRunner.archive(report, spec);
                }
            } else {
                CorpusProofRunner.Spec spec = CorpusProofRunner.loadDir(dir);
                report = new CorpusProofRunner().evaluate(spec, work);
                minCov = spec.minParamCoverage();
                minSp = spec.minAvgSpeedup();
                if (archive) {
                    CorpusProofRunner.archive(report, spec);
                }
            }
            System.out.printf(Locale.US,
                    "corpus=%s param_coverage=%.1f%% return_coverage=%.1f%% compile=%s correct=%.0f%% avg_speedup=%.2fx gates=%s%n",
                    report.corpusId(),
                    100 * report.coverage().paramCoverage(),
                    100 * report.coverage().returnCoverage(),
                    report.compileOk() ? "ok" : "FAIL",
                    100 * report.correctRate(),
                    report.avgSpeedup(),
                    report.gatesPass(minCov, minSp));
            if (!report.compileOk()) {
                System.err.println(report.compileError());
            }
            for (CorpusProofRunner.BenchRow r : report.rows()) {
                System.out.printf(Locale.US, "  %s correct=%s speedup=%.2fx%n",
                        r.func(), r.correct(), r.speedupVsNative());
            }
            if (archive) {
                System.err.println("Archived under " + EvalResultArchive.defaultRoot());
            }
            return report.gatesPass(minCov, minSp) ? 0 : 1;
        } catch (IOException e) {
            System.err.println("I/O error: " + e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            System.err.println("Corpus proof failed: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }
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
        ps.println("      [--bench cases.json|inline-json]");
        ps.println("      Flow with --bench:");
        ps.println("        1) Meridian annotate + mypyc compile");
        ps.println("        2) eval result == native CPython");
        ps.println("        3) eval performance vs native CPython");
        ps.println("      cases JSON: [[\"fn\",[args],iters], ...]");
        ps.println("  meridian compile --pkg <dir> --primary <mod> [-o out_dir]");
        ps.println("      [--calls usage.py | --calls-inline CODE] [--annotate-all]");
        ps.println("      [--annotation-mode keep_deps|strip_deps|keep|strip_all]");
        ps.println("      [--compile-modules a,b | --compile-imports]");
        ps.println("      Default mode keep_deps; empty compile set → import closure");
        ps.println("  meridian corpus <corpus_dir> [--archive]");
        ps.println("      Four gates: coverage → mypyc → correctness → speedup");
        ps.println("      Dir layout: recipes.py calls.py cases.json manifest.json");
        ps.println("  meridian version | help");
        ps.println();
        ps.println("Specialization: every distinct concrete call-site type tuple");
        ps.println("gets a clone + isinstance dispatcher.");
        ps.println("See meridian-python/docs/mypyc-compile-and-ide-plan.md");
    }

    private MeridianCli() {}
}
