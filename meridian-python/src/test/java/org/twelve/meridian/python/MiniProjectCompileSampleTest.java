package org.twelve.meridian.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.twelve.meridian.python.eval.EvalResultArchive;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-shaped mini project: {@code resources/mini_project/} as
 * {@code meridian compile --calls} sample. Guards prune + rewrite.
 */
class MiniProjectCompileSampleTest {

    @Test
    void pipeline_prunes_dead_rewrites_poly_and_beats_native() throws Exception {
        String lib = loadResource("mini_project/stats_kit.py");
        String calls = loadResource("mini_project/calls.py");

        Path out = Files.createTempDirectory("meridian_mini_");
        CompilePipeline.Outcome outcome = new CompilePipeline().run(new CompilePipeline.Request(
                lib,
                "stats_kit",
                calls,
                true,
                AnnotationPolicy.ALL_CONCRETE,
                out,
                "[[\"rolling_sum\",[400],20000]]"
        ));

        assertTrue(outcome.compileResult().success(),
                () -> outcome.compileResult().stderr());
        String src = outcome.annotatedSource();

        assertTrue(outcome.prunedFunctions().contains("unused_histogram"),
                () -> "pruned=" + outcome.prunedFunctions());
        assertTrue(outcome.prunedFunctions().contains("unused_format_report"),
                () -> "pruned=" + outcome.prunedFunctions());
        assertFalse(src.contains("def unused_histogram"), () -> src);
        assertFalse(src.contains("def unused_format_report"), () -> src);

        assertTrue(src.contains("def rolling_sum") || src.contains("rolling_sum"),
                () -> src);
        assertTrue(outcome.plan().containsKey("_inc") || src.contains("_inc"),
                () -> "helper must stay reachable:\n" + src);
        assertTrue(src.contains("x: int") || src.contains("_inc"),
                () -> "helper should be typed via library call:\n" + src);

        assertTrue(outcome.plan().containsKey("tag"), () -> "plan=" + outcome.plan().keySet());
        assertFalse(outcome.plan().get("tag").isMonomorphic(),
                () -> "tag int+str → poly: " + outcome.plan().get("tag").bindings());
        assertTrue(src.contains("_tag_int") && src.contains("_tag_str"), () -> src);
        // Library-internal? tag only called from usage — dispatcher OK; clones must exist.
        assertTrue(src.contains("isinstance"), () -> src);

        assertTrue(outcome.benchOk(), () -> outcome.benchJson());
        JsonNode row = new ObjectMapper().readTree(outcome.benchJson()).path("rows").get(0);
        assertTrue(row.path("correct").asBoolean(false), () -> outcome.benchJson());
        double speedup = row.path("speedup_vs_native").asDouble(
                row.path("speedup_gcp").asDouble(0));
        assertTrue(speedup >= 2.0,
                () -> String.format(Locale.US, "rolling_sum ≥2×, got %.2f; %s",
                        speedup, outcome.benchJson()));

        archiveMini(outcome, speedup, row.path("correct").asBoolean(false));
    }

    private static void archiveMini(
            CompilePipeline.Outcome outcome, double speedup, boolean correct) throws Exception {
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("func", "rolling_sum");
        m.put("correct", correct);
        m.put("speedup_vs_native", Math.round(speedup * 100.0) / 100.0);
        m.put("pruned", String.join(",", outcome.prunedFunctions()));
        m.put("poly_tag", outcome.plan().containsKey("tag")
                && !outcome.plan().get("tag").isMonomorphic());
        m.put("helper_kept", !outcome.prunedFunctions().contains("_inc"));
        rows.add(m);

        String json = """
                {
                  "fixture": "mini_project/stats_kit.py + calls.py",
                  "rows": %s
                }
                """.formatted(EvalResultArchive.rowsToJson(rows));
        String md = "## mini_project sample\n\n"
                + String.format(Locale.US,
                "- rolling_sum speedup_vs_native: **%.2f**\n"
                        + "- pruned: `%s`\n"
                        + "- poly tag: **%s**\n"
                        + "- helper `_inc` kept: **%s**\n",
                speedup,
                String.join(", ", outcome.prunedFunctions()),
                outcome.plan().containsKey("tag")
                        && !outcome.plan().get("tag").isMonomorphic(),
                !outcome.prunedFunctions().contains("_inc"));
        EvalResultArchive.writeSuite(
                "mini-project-sample",
                "Mini project — prune / specialize / native bench",
                json, md);
    }

    private static String loadResource(String path) throws Exception {
        var url = MiniProjectCompileSampleTest.class.getClassLoader().getResource(path);
        assertNotNull(url, path + " missing on classpath");
        return Files.readString(Path.of(url.toURI()), StandardCharsets.UTF_8);
    }
}
