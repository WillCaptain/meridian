package org.twelve.meridian.python;

import org.junit.jupiter.api.Test;
import org.twelve.gcp.ast.AST;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Outline-style optional/parametric bindings at call sites.
 *
 * <p>{@code str}/{@code int} is one fixture; the same monomorphization rule must
 * apply to every distinct concrete type tuple GCP exposes (also covered by
 * int/float in {@link MonomorphizationTest}).
 */
class PolyOptionalCallSitesTest {

    @Test
    void multi_concrete_call_sites_get_dispatcher_and_clones() throws Exception {
        // Body valid for str and int (Outline spirit: f used at multiple bindings).
        String lib = """
                def f(x):
                    return x + x
                """;
        String usage = """
                f(100)
                f("ab")
                """;

        PythonInferencer inf = new PythonInferencer();
        AST[] asts = inf.inferWithContext(lib, usage);
        FunctionSpecializer spec = new FunctionSpecializer();
        Map<String, FunctionSpecializer.FuncSpecializations> plan =
                spec.analyse(asts[0], asts[1]);

        assertTrue(plan.containsKey("f"), () -> "plan=" + plan.keySet());
        FunctionSpecializer.FuncSpecializations fs = plan.get("f");
        assertFalse(fs.isMonomorphic(), "str and int call sites must yield 2+ bindings");
        assertEquals(2, fs.bindings().size(), () -> "bindings=" + fs.bindings());

        String specialized = spec.specialize(lib, plan);
        assertTrue(specialized.contains("isinstance"), () -> specialized);
        assertTrue(specialized.contains("_f_int") && specialized.contains("_f_str"),
                () -> specialized);

        Path work = Files.createTempDirectory("meridian_poly_opt_");
        Path py = work.resolve("poly_f.py");
        Files.writeString(py, specialized, StandardCharsets.UTF_8);
        MypycRunner.CompileResult cr = new MypycRunner().compile(py.toFile(), work.toFile());
        assertTrue(cr.success(), () -> "mypyc must compile specialized poly source:\n" + cr.stderr());

        // Runtime correctness through dispatcher (CPython import of .so).
        String verify = """
                import importlib.util, sys
                so = %s
                spec = importlib.util.spec_from_file_location("poly_f", so)
                m = importlib.util.module_from_spec(spec)
                sys.modules["poly_f"] = m
                spec.loader.exec_module(m)
                assert m.f(21) == 42
                assert m.f("ab") == "abab"
                print("ok")
                """.formatted(toPyString(cr.outputFile().getAbsolutePath()));
        Path script = work.resolve("verify.py");
        Files.writeString(script, verify, StandardCharsets.UTF_8);
        Process p = new ProcessBuilder("python3", script.toString())
                .directory(work.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), () -> out);
        assertTrue(out.contains("ok"), out);
    }

    @Test
    void compile_pipeline_specializes_any_multi_concrete_tuple() throws Exception {
        String lib = """
                def f(x):
                    return x + x
                """;
        Path out = Files.createTempDirectory("meridian_compile_poly_");
        CompilePipeline.Outcome outcome = new CompilePipeline().run(new CompilePipeline.Request(
                lib,
                "poly_pipe",
                "f(1)\nf(\"z\")\n",
                true,
                AnnotationPolicy.ALL_CONCRETE,
                out,
                null
        ));
        assertTrue(outcome.specialized(), "usage with two concrete types must specialize");
        assertTrue(outcome.compileResult().success(),
                () -> outcome.compileResult().stderr());
        assertTrue(outcome.annotatedSource().contains("isinstance"));
    }

    private static String toPyString(String path) {
        return "\"" + path.replace("\\", "\\\\") + "\"";
    }
}
