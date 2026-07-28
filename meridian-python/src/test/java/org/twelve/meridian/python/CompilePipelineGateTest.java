package org.twelve.meridian.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase-4 product gate: usage → specialize / prune → mypyc → correct vs native
 * (and faster than native on monomorphic hot loops).
 */
class CompilePipelineGateTest {

    @Test
    void poly_and_prune_compile_correct_vs_native() throws Exception {
        String lib = """
                def f(x):
                    return x + x

                def dead(n):
                    return n * n * n
                """;
        String usage = """
                f(3)
                f("ab")
                """;
        Path out = Files.createTempDirectory("meridian_gate_poly_");
        CompilePipeline.Outcome outcome = new CompilePipeline().run(new CompilePipeline.Request(
                lib,
                "gate_poly",
                usage,
                true,
                AnnotationPolicy.ALL_CONCRETE,
                out,
                "[[\"f\",[21],50000]]"
        ));

        assertTrue(outcome.compileResult().success(),
                () -> outcome.compileResult().stderr());
        assertTrue(outcome.prunedFunctions().contains("dead"),
                () -> "pruned=" + outcome.prunedFunctions());
        assertFalse(outcome.annotatedSource().contains("def dead"),
                () -> outcome.annotatedSource());
        assertTrue(outcome.annotatedSource().contains("isinstance"),
                () -> outcome.annotatedSource());
        assertTrue(outcome.benchOk(), () -> outcome.benchJson());
        JsonNode row = new ObjectMapper().readTree(outcome.benchJson()).path("rows").get(0);
        assertTrue(row.path("correct").asBoolean(false), () -> outcome.benchJson());
        // Dispatcher path: correctness is the gate; speedup may be modest.
        assertTrue(row.path("speedup_vs_native").asDouble(0) >= 1.0
                        || row.path("speedup_gcp").asDouble(0) >= 1.0,
                () -> outcome.benchJson());
    }

    @Test
    void helper_kept_unused_pruned_and_correct() throws Exception {
        String lib = """
                def helper(x):
                    return x + 1

                def hot(n):
                    total = 0
                    for i in range(n):
                        total += helper(i)
                    return total

                def unused(n):
                    return n * n
                """;
        Path out = Files.createTempDirectory("meridian_gate_hot_");
        CompilePipeline.Outcome outcome = new CompilePipeline().run(new CompilePipeline.Request(
                lib,
                "gate_hot",
                "hot(30)\n",
                true,
                AnnotationPolicy.ALL_CONCRETE,
                out,
                "[[\"hot\",[500],8000]]"
        ));

        assertTrue(outcome.compileResult().success(),
                () -> outcome.compileResult().stderr());
        assertTrue(outcome.prunedFunctions().contains("unused"),
                () -> "pruned=" + outcome.prunedFunctions());
        assertFalse(outcome.prunedFunctions().contains("helper"),
                () -> "helper must stay reachable; pruned=" + outcome.prunedFunctions());
        assertTrue(outcome.annotatedSource().contains("def helper"),
                () -> outcome.annotatedSource());
        assertTrue(outcome.benchOk(), () -> outcome.benchJson());
        JsonNode row = new ObjectMapper().readTree(outcome.benchJson()).path("rows").get(0);
        assertTrue(row.path("correct").asBoolean(false), () -> outcome.benchJson());
        // Cross-function call often limits mypyc; speedup gate lives on sum_range benches.
    }

    @Test
    void monomorphic_unused_dropped_still_correct() throws Exception {
        String lib = """
                def sum_range(n):
                    total = 0
                    for i in range(n):
                        total += i
                    return total

                def unused_fact(n):
                    r = 1
                    for i in range(1, n + 1):
                        r *= i
                    return r
                """;
        Path out = Files.createTempDirectory("meridian_gate_mono_");
        CompilePipeline.Outcome outcome = new CompilePipeline().run(new CompilePipeline.Request(
                lib,
                "mono",
                "sum_range(50)\n",
                true,
                AnnotationPolicy.ALL_CONCRETE,
                out,
                "[[\"sum_range\",[800],20000]]"
        ));

        assertTrue(outcome.compileResult().success(), () -> outcome.compileResult().stderr());
        assertTrue(outcome.prunedFunctions().contains("unused_fact"),
                () -> "pruned=" + outcome.prunedFunctions());
        assertTrue(outcome.benchOk(), () -> outcome.benchJson());
        JsonNode row = new ObjectMapper().readTree(outcome.benchJson()).path("rows").get(0);
        assertTrue(row.path("correct").asBoolean(false), () -> outcome.benchJson());
        double speedup = row.path("speedup_vs_native").asDouble(
                row.path("speedup_gcp").asDouble(0));
        assertTrue(speedup >= 2.0,
                () -> String.format("pruned monomorphic hot path ≥2×, got %.2f; %s",
                        speedup, outcome.benchJson()));
    }
}
