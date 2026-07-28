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
 * L3 multi-module proof on a more-itertools-shaped package
 * ({@code mi_facade → mi_recipes → mi_numeric}).
 */
class ProductionCorpusL3ProofTest {

    @Test
    void l3_more_itertools_package_four_gates() throws Exception {
        Path dir = resourceDir("production_corpus/l3_more_itertools");
        PackageCorpusProofRunner.PackageSpec spec = PackageCorpusProofRunner.loadDir(dir);
        Path work = Files.createTempDirectory("meridian_l3_");
        CorpusProofRunner.Report report = new PackageCorpusProofRunner().evaluate(spec, work);

        assertTrue(report.compileOk(),
                () -> "mypyc multi-file compile failed:\n" + report.compileError());
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
                        "avg speedup %.2f < %.1f; rows=%s",
                        report.avgSpeedup(), spec.minAvgSpeedup(), report.rows()));

        // Cross-module edge must remain in annotated recipes.
        assertTrue(report.annotatedSource().contains("mi_numeric")
                        || report.annotatedSource().contains("tabulate_sum")
                        || report.annotatedSource().contains("mul_acc"),
                () -> "recipes should keep cross-module imports/calls:\n"
                        + report.annotatedSource());

        PackageCorpusProofRunner.archive(report, spec);
    }

    private static Path resourceDir(String path) throws Exception {
        URL url = ProductionCorpusL3ProofTest.class.getClassLoader()
                .getResource(path + "/manifest.json");
        assertNotNull(url, path + " missing on classpath");
        return Path.of(url.toURI()).getParent();
    }
}
