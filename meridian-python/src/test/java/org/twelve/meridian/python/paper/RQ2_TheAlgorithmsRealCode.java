package org.twelve.meridian.python.paper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RQ2.6 — TheAlgorithms/Python 真实代码基准（博士论文 §6.3.6，表 6-6）。
 *
 * <p>实验员策略：反射启动既有
 * {@code org.twelve.meridian.python.TheAlgorithmsBenchmarkTest}，捕获其 stdout 到
 * {@code target/paper-reports/rq2-thealgorithms-raw.log} 与解析后的 CSV，供论文 §6.3.6 表 6-6 回填。
 *
 * <p>既有测试涵盖：从 TheAlgorithms/Python 仓库**逐字提取**的数论 / 排序 / 搜索三类共 18 个
 * 函数，跑 CPython vs mypyc(GCP) 对比，验证 GCP-Python 在真实工业代码上的有效性
 * （而非合成微基准的偏向）。
 */
@Tag("paper")
public class RQ2_TheAlgorithmsRealCode {

    private static final String DELEGATE_FQCN = "org.twelve.meridian.python.TheAlgorithmsBenchmarkTest";
    private static final String DELEGATE_METHOD = "the_algorithms_real_world_benchmark";
    private static final Path RAW_LOG = Paths.get("target/paper-reports/rq2-thealgorithms-raw.log");
    private static final Path EXTRACTED_CSV = Paths.get("target/paper-reports/rq2-thealgorithms-extracted.csv");

    @BeforeAll
    static void ensureReportsDir() throws IOException {
        Files.createDirectories(RAW_LOG.getParent());
    }

    @Test
    void rq2_6_invoke_thealgorithms_benchmark_and_capture_stdout() throws Exception {
        DelegatingBenchmarkRunner.CaptureResult result =
                DelegatingBenchmarkRunner.runAndCapture(DELEGATE_FQCN, DELEGATE_METHOD, RAW_LOG);

        DelegatingBenchmarkRunner.extractNumericLines(result.capturedStdout(), EXTRACTED_CSV);

        System.out.printf("%n[RQ2.6] Delegate ran in %d ms; raw stdout → %s%n",
                result.elapsedMs(), RAW_LOG);

        if (!result.passed()) {
            fail("Delegate benchmark failed: " + result.failure() +
                 "\n查看 " + RAW_LOG + " 获取完整 stdout");
        }
    }
}
