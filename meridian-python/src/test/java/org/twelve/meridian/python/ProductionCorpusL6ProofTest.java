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
 * L6: nearly-full {@code more.py} coverage + Meridian-annotated hot-path mypyc.
 *
 * <p>Unlike L5 ({@code strip_deps}), L6 keeps parameter annotations on the hot
 * subgraph so typed mypyc can win on larger inputs.
 */
class ProductionCorpusL6ProofTest {

    @Test
    void l6_full_more_coverage_annotated_hot_path() throws Exception {
        Path dir = resourceDir("production_corpus/l6_more_itertools_full");
        PackageCorpusProofRunner.PackageSpec spec = PackageCorpusProofRunner.loadDir(dir);
        assertTrue(spec.modules().containsKey("mi_more_full"), "full more.py surface required");
        assertTrue(spec.modules().get("mi_more_full").length() > 80_000,
                "more.py extract should be large");
        assertTrue("keep_deps".equalsIgnoreCase(spec.annotationMode()),
                "L6 must keep Meridian annotations on hot deps for mypyc");
        assertTrue(spec.compileModules().contains("mi_hot"),
                "hot subgraph must be in compile set");

        Path work = Files.createTempDirectory("meridian_l6_");
        CorpusProofRunner.Report report = new PackageCorpusProofRunner().evaluate(spec, work);

        assertTrue(report.compileOk(),
                () -> "mypyc hot-path compile failed:\n" + report.compileError()
                        + "\n--- primary ---\n" + preview(report.annotatedSource()));
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
        assertTrue(report.coverage().funcsTotal() >= 80,
                () -> "L6 should scan nearly-full more.py, funcs="
                        + report.coverage().funcsTotal());
        // Hot-module annotations are kept (keep_deps); primary facade is stripped on purpose.
        assertTrue(Files.readString(work.resolve("meridian/mi_hot.py")).contains("list[")
                        || Files.readString(work.resolve("meridian/mi_hot.py")).contains(": int"),
                () -> "L6 hot module should retain Meridian param annotations");

        PackageCorpusProofRunner.archive(report, spec);
    }

    private static Path resourceDir(String path) throws Exception {
        URL url = ProductionCorpusL6ProofTest.class.getClassLoader()
                .getResource(path + "/manifest.json");
        assertNotNull(url, path + " missing on classpath");
        return Path.of(url.toURI()).getParent();
    }

    private static String preview(String src) {
        if (src == null) return "";
        return src.length() <= 1500 ? src : src.substring(0, 1500) + "\n…";
    }
}
