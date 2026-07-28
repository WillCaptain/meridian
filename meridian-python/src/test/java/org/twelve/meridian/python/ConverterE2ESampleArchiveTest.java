package org.twelve.meridian.python;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.twelve.meridian.python.eval.EvalResultArchive;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Curated Converter-E2E sample for paper refresh (faster than full
 * {@link ConverterE2ETest}). Uses the product {@link CompilePipeline}
 * and archives under {@code docs/meridian-eval/}.
 */
class ConverterE2ESampleArchiveTest {

    private record Sample(
            String label, String lib, String calls, String benchJson, String expectAnnot) {}

    @Test
    void archive_sample_converters() throws Exception {
        List<Sample> samples = List.of(
                new Sample(
                        "aug_assign",
                        """
                                def sum_to(n):
                                    total = 0
                                    i = 0
                                    while i < n:
                                        total += i
                                        i += 1
                                    return total
                                """,
                        "sum_to(100)\n",
                        "[[\"sum_to\",[500],80000]]",
                        "n: int"),
                new Sample(
                        "listcomp",
                        """
                                def squares(n):
                                    return sum([i * i for i in range(n)])
                                """,
                        "squares(50)\n",
                        "[[\"squares\",[200],40000]]",
                        "n: int"),
                new Sample(
                        "for_loop_var",
                        """
                                def sum_range(n):
                                    total = 0
                                    for i in range(n):
                                        total += i
                                    return total
                                """,
                        "sum_range(100)\n",
                        "[[\"sum_range\",[1000],30000]]",
                        "n: int"),
                new Sample(
                        "ifexp",
                        """
                                def abs_diff(a, b):
                                    return a - b if a > b else b - a
                                def sum_abs(n):
                                    total = 0
                                    for i in range(n):
                                        total += abs_diff(i, n - i)
                                    return total
                                """,
                        "sum_abs(50)\nabs_diff(3, 10)\n",
                        "[[\"sum_abs\",[200],40000]]",
                        "n: int")
        );

        List<Map<String, Object>> rows = new ArrayList<>();
        StringBuilder md = new StringBuilder("## Sample converters\n\n");
        md.append("| Converter | correct | speedup_vs_native |\n");
        md.append("|-----------|---------|-------------------|\n");

        for (Sample s : samples) {
            Path out = Files.createTempDirectory("meridian_e2e_sample_" + s.label + "_");
            CompilePipeline.Outcome outcome = new CompilePipeline().run(new CompilePipeline.Request(
                    s.lib(),
                    "hot_" + s.label,
                    s.calls(),
                    true,
                    AnnotationPolicy.ALL_CONCRETE,
                    out,
                    s.benchJson()
            ));
            assertTrue(outcome.compileResult().success(),
                    () -> s.label() + " compile failed:\n" + outcome.compileResult().stderr());
            assertTrue(outcome.annotatedSource().contains(s.expectAnnot()),
                    () -> s.label() + " missing " + s.expectAnnot() + ":\n" + outcome.annotatedSource());
            assertTrue(outcome.benchOk(),
                    () -> s.label() + " bench failed:\n" + outcome.benchJson());

            JsonNode root = new ObjectMapper().readTree(outcome.benchJson());
            double avgSpeedup = 0;
            boolean allCorrect = true;
            int n = 0;
            if (root.has("rows")) {
                for (JsonNode r : root.get("rows")) {
                    if (r.has("correct") && !r.get("correct").asBoolean(false)) {
                        allCorrect = false;
                    }
                    if (r.has("speedup_vs_native")) {
                        avgSpeedup += r.get("speedup_vs_native").asDouble();
                        n++;
                    } else if (r.has("speedup_gcp")) {
                        avgSpeedup += r.get("speedup_gcp").asDouble();
                        n++;
                    }
                }
            }
            if (n > 0) avgSpeedup /= n;

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("converter", s.label());
            m.put("correct", allCorrect);
            m.put("speedup_vs_native", Math.round(avgSpeedup * 100.0) / 100.0);
            rows.add(m);
            md.append(String.format(Locale.US, "| `%s` | %s | %.2f |\n",
                    s.label(), allCorrect, avgSpeedup));
            System.out.printf("  [%s] correct=%s speedup_vs_native=%.2fx%n",
                    s.label(), allCorrect, avgSpeedup);
        }

        String json = """
                {
                  "note": "Curated subset via CompilePipeline (product path)",
                  "rows": %s
                }
                """.formatted(EvalResultArchive.rowsToJson(rows));
        Path written = EvalResultArchive.writeSuite(
                "converter-e2e-sample",
                "Converter E2E sample — Meridian CompilePipeline",
                json, md.toString());
        System.out.println("  Archived → " + written.toAbsolutePath());
        assertTrue(Files.isRegularFile(written));
    }
}
