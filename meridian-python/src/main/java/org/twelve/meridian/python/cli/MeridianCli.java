package org.twelve.meridian.python.cli;

import org.twelve.gcp.ast.AST;
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
 *   meridian infer  path.py [-o out.py]
 *   meridian stub   path.py [-o out.pyi]
 *   meridian sites  path.py [-o main_result.json]
 * </pre>
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
        if (!"infer".equals(cmd) && !"stub".equals(cmd) && !"sites".equals(cmd)) {
            System.err.println("Unknown command: " + cmd);
            printHelp(System.err);
            return 2;
        }

        List<String> rest = new ArrayList<>();
        Path out = null;
        for (int i = 1; i < args.length; i++) {
            String a = args[i];
            if ("-o".equals(a) || "--output".equals(a)) {
                if (i + 1 >= args.length) {
                    System.err.println(a + " requires a path");
                    return 2;
                }
                out = Path.of(args[++i]);
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
            System.err.println("Usage: meridian " + cmd + " <file.py> [-o path]");
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
                default -> writeAnnotated(input, out);
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
        PythonInferencer inferencer = new PythonInferencer();
        AST ast = inferencer.inferFile(input);
        String stub = new TypeAnnotationGenerator().generate(ast);
        return emit(stub, out);
    }

    private static int writeAnnotated(File input, Path out) throws IOException {
        String source = Files.readString(input.toPath(), StandardCharsets.UTF_8);
        PythonInferencer inferencer = new PythonInferencer();
        PythonInferencer.InferResult inferred = inferencer.inferFileDetailed(input);
        String annotated = new PythonAnnotationWriter().annotate(source, inferred.inference());
        return emit(annotated, out);
    }

    private static int writeSites(File input, Path out) throws IOException {
        PythonInferencer.InferResult inferred = new PythonInferencer().inferFileDetailed(input);
        TypeEvalPySiteExporter exporter = new TypeEvalPySiteExporter();
        List<Map<String, Object>> sites = exporter.collect(inferred.inference());
        String json = exporter.toJson(sites);
        return emit(json, out);
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
        ps.println("Meridian — GCP Python type inferencer");
        ps.println();
        ps.println("Usage:");
        ps.println("  meridian infer <file.py> [-o out.py]            Annotate source (PEP 484)");
        ps.println("  meridian stub  <file.py> [-o out.pyi]           Emit .pyi stub");
        ps.println("  meridian sites <file.py> [-o main_result.json]  TypeEvalPy site JSON");
        ps.println("  meridian version");
        ps.println("  meridian help");
        ps.println();
        ps.println("See MERIDIAN-PRODUCT-ROADMAP.md for phases (CLI → TypeEvalPy → IDE → mypyc).");
    }

    private MeridianCli() {}
}
