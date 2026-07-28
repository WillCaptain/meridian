package org.twelve.meridian.python;

import org.junit.jupiter.api.Test;
import org.twelve.meridian.python.eval.CorpusProofRunner;
import org.twelve.meridian.python.eval.PackageCorpusProofRunner;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L5: upstream more-itertools package surface — full recipes + selected more.py,
 * multi-module import graph, hot-facade bench.
 */
class ProductionCorpusL5ProofTest {

    @Test
    void l5_more_itertools_package_four_gates() throws Exception {
        Path dir = resourceDir("production_corpus/l5_more_itertools_pkg");
        PackageCorpusProofRunner.PackageSpec spec = PackageCorpusProofRunner.loadDir(dir);
        assertTrue(spec.modules().containsKey("mi_recipes"), "full recipes module required");
        assertTrue(spec.modules().get("mi_recipes").length() > 20_000,
                "recipes extract should be large upstream surface");

        Path work = Files.createTempDirectory("meridian_l5_");
        CorpusProofRunner.Report report = new PackageCorpusProofRunner().evaluate(spec, work);

        assertTrue(report.compileOk(),
                () -> "mypyc multi-file compile failed:\n" + report.compileError()
                        + "\n--- primary annotated preview ---\n"
                        + preview(report.annotatedSource()));
        assertTrue(report.coverage().paramCoverage() + 1e-9 >= spec.minParamCoverage(),
                () -> String.format(Locale.US,
                        "param coverage %.1f%% < %.0f%%; unannotated=%s",
                        100 * report.coverage().paramCoverage(),
                        100 * spec.minParamCoverage(),
                        report.coverage().unannotatedFuncs()));
        assertTrue(report.correctRate() + 1e-9 >= 1.0,
                () -> "correctness failed: " + report.rows());
        assertTrue(report.avgSpeedup() + 1e-9 >= spec.minAvgSpeedup(),
                () -> String.format(Locale.US,
                        "avg speedup %.2f < %.2f; rows=%s",
                        report.avgSpeedup(), spec.minAvgSpeedup(), report.rows()));
        assertTrue(report.coverage().funcsTotal() >= 40,
                () -> "L5 should scan a large upstream surface, funcs="
                        + report.coverage().funcsTotal());

        PackageCorpusProofRunner.archive(report, spec);
    }

    private static Path resourceDir(String path) throws Exception {
        URL url = ProductionCorpusL5ProofTest.class.getClassLoader()
                .getResource(path + "/manifest.json");
        assertNotNull(url, path + " missing on classpath");
        return Path.of(url.toURI()).getParent();
    }

    private static String preview(String src) {
        if (src == null) return "";
        return src.length() <= 1500 ? src : src.substring(0, 1500) + "\n…";
    }
}
