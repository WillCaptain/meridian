package org.twelve.meridian.python;

import org.junit.jupiter.api.Test;
import org.twelve.meridian.python.eval.CorpusProofRunner;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L2 production-shaped corpus proof (more-itertools recipes subset).
 *
 * <p>Four gates: annotation coverage, mypyc compile, native correctness,
 * speedup vs native. Archives under {@code docs/meridian-eval/}.
 */
class ProductionCorpusProofTest {

    @Test
    void l2_more_itertools_four_gates() throws Exception {
        Path dir = resourceDir("production_corpus/l2_more_itertools");
        CorpusProofRunner.Spec spec = CorpusProofRunner.loadDir(dir);
        Path work = Files.createTempDirectory("meridian_corpus_");
        CorpusProofRunner.Report report = new CorpusProofRunner().evaluate(spec, work);

        assertTrue(report.compileOk(),
                () -> "mypyc compile failed:\n" + report.compileError());
        assertTrue(report.coverage().paramCoverage() + 1e-9 >= spec.minParamCoverage(),
                () -> String.format(Locale.US,
                        "param coverage %.1f%% < %.0f%%; unannotated=%s\nannotated preview:\n%s",
                        100 * report.coverage().paramCoverage(),
                        100 * spec.minParamCoverage(),
                        report.coverage().unannotatedFuncs(),
                        preview(report.annotatedSource())));
        assertTrue(report.correctRate() + 1e-9 >= 1.0,
                () -> "correctness failed: " + report.rows());
        assertTrue(report.avgSpeedup() + 1e-9 >= spec.minAvgSpeedup(),
                () -> String.format(Locale.US,
                        "avg speedup %.2f < %.1f; rows=%s",
                        report.avgSpeedup(), spec.minAvgSpeedup(), report.rows()));
        assertTrue(report.gatesPass(spec.minParamCoverage(), spec.minAvgSpeedup()));

        // Leave-gap probe should appear among partially unannotated when HOF is hard.
        // Soft check: apply_twice is in coverage usage; if fully typed that's fine too.
        CorpusProofRunner.archive(report, spec);
    }

    private static Path resourceDir(String path) throws Exception {
        URL url = ProductionCorpusProofTest.class.getClassLoader().getResource(path + "/manifest.json");
        assertNotNull(url, path + " missing on classpath");
        return Path.of(url.toURI()).getParent();
    }

    private static String preview(String src) {
        if (src == null) return "";
        return src.length() <= 1200 ? src : src.substring(0, 1200) + "\n…";
    }
}
