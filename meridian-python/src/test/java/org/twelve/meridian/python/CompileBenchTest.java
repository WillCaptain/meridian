package org.twelve.meridian.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Product gate: Meridian compile → native eval correctness → native eval performance.
 */
class CompileBenchTest {

    @Test
    void compile_checks_result_and_speed_against_native() throws Exception {
        String lib = """
                def sum_range(n):
                    total = 0
                    for i in range(n):
                        total += i
                    return total
                """;
        Path out = Files.createTempDirectory("meridian_compile_bench_");
        CompilePipeline.Outcome outcome = new CompilePipeline().run(new CompilePipeline.Request(
                lib,
                "hot",
                "sum_range(1000)\n",
                true,
                AnnotationPolicy.ALL_CONCRETE,
                out,
                "[[\"sum_range\",[1000],20000]]"
        ));

        assertTrue(outcome.compileResult().success(), () -> outcome.compileResult().stderr());
        assertTrue(outcome.benchOk(), () -> "native eval failed:\n" + outcome.benchJson());
        assertTrue(Files.isRegularFile(out.resolve("hot_native.py")), "naked native baseline");
        assertTrue(Files.isRegularFile(out.resolve("hot.py")), "Meridian annotated source");
        assertTrue(outcome.benchJson().contains("speedup_vs_native")
                        || outcome.benchJson().contains("speedup_gcp"),
                () -> outcome.benchJson());
        assertTrue(outcome.benchJson().contains("\"correct\": true")
                        || outcome.benchJson().contains("\"correct\":true"),
                () -> outcome.benchJson());

        JsonNode root = new ObjectMapper().readTree(outcome.benchJson());
        double speedup = root.path("rows").get(0).path("speedup_vs_native").asDouble(
                root.path("rows").get(0).path("speedup_gcp").asDouble(0));
        assertTrue(speedup >= 3.0,
                () -> String.format("sum_range must be ≥3× vs native, got %.2f; %s",
                        speedup, outcome.benchJson()));
    }
}
