package org.twelve.meridian.python;

import org.junit.jupiter.api.Test;
import org.twelve.gcp.ast.AST;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Library-internal call sites feed the specialize plan; poly calls rewrite to clones.
 */
class SpecializeCallRewriteTest {

    @Test
    void library_callee_enters_plan_when_usage_only_names_entry() {
        String lib = """
                def helper(x):
                    return x + 1

                def hot(n):
                    total = 0
                    for i in range(n):
                        total += helper(i)
                    return total
                """;
        AST[] asts = new PythonInferencer().inferWithContext(lib, "hot(10)\n");
        Map<String, FunctionSpecializer.FuncSpecializations> plan =
                new FunctionSpecializer().analyse(asts[0], asts[1]);

        assertTrue(plan.containsKey("hot"), () -> "plan=" + plan.keySet());
        assertTrue(plan.containsKey("helper"),
                () -> "library call helper(i) must enter plan; plan=" + plan.keySet());
        assertTrue(plan.get("helper").isMonomorphic());
        assertEquals("int", plan.get("helper").primary().argTypes().getFirst());
    }

    @Test
    void poly_library_call_rewrites_to_clone() {
        String lib = """
                def f(x):
                    return x + x

                def hot(n):
                    return f(n)
                """;
        String usage = """
                hot(3)
                f("ab")
                """;
        AST[] asts = new PythonInferencer().inferWithContext(lib, usage);
        FunctionSpecializer spec = new FunctionSpecializer();
        Map<String, FunctionSpecializer.FuncSpecializations> plan =
                spec.analyse(asts[0], asts[1]);

        assertFalse(plan.get("f").isMonomorphic(), () -> plan.get("f").bindings().toString());
        String out = spec.specialize(lib, plan, asts[0]);
        assertTrue(out.contains("_f_int"), () -> out);
        assertTrue(out.contains("return _f_int(n)") || out.contains("_f_int(n)"),
                () -> "hot should call clone directly:\n" + out);
        assertFalse(out.matches("(?s).*def hot\\(.*\\):\\s*return f\\(n\\).*"),
                () -> "hot must not call polymorphic f through dispatcher:\n" + out);
    }

    @Test
    void poly_rewrite_pipeline_hot_beats_native() throws Exception {
        // Body must be valid for every concrete binding (int and str).
        String lib = """
                def f(x):
                    return x + x

                def hot(n):
                    total = 0
                    for i in range(n):
                        total += f(i)
                    return total
                """;
        // Multi-concrete on f; hot's loop should call _f_int directly.
        Path out = Files.createTempDirectory("meridian_rewrite_");
        CompilePipeline.Outcome outcome = new CompilePipeline().run(new CompilePipeline.Request(
                lib,
                "rw",
                "hot(20)\nf(\"z\")\n",
                true,
                AnnotationPolicy.ALL_CONCRETE,
                out,
                "[[\"hot\",[400],20000]]"
        ));
        assertTrue(outcome.compileResult().success(), () -> outcome.compileResult().stderr());
        assertTrue(outcome.annotatedSource().contains("_f_int(i)"),
                () -> outcome.annotatedSource());
        assertTrue(outcome.benchOk(), () -> outcome.benchJson());
        String json = outcome.benchJson();
        assertTrue(json.contains("\"correct\": true") || json.contains("\"correct\":true"),
                () -> json);
        // Direct clone on the hot path should beat a dispatcher-heavy call.
        assertTrue(json.contains("speedup_vs_native") || json.contains("speedup_gcp"),
                () -> json);
    }
}
