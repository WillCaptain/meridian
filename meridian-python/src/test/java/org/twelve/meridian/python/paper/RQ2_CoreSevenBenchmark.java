package org.twelve.meridian.python.paper;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RQ2.1 — 核心 7 函数 oracle 基准（博士论文 §6.3.1，表 6-2）。
 *
 * <p>实验员策略：本类**不重新实现**核心 7 函数基准——它反射启动既有
 * {@code org.twelve.meridian.python.Table1BenchmarkTest}（"Produces data for CGO §2.2 Table 1
 * and §5.3 Table 3"），捕获其 stdout 到 {@code target/paper-reports/rq2-core7-raw.log}
 * 与解析后的 CSV，供论文 §6.3.1 表 6-2 回填。
 *
 * <p>Table1BenchmarkTest 实测包含四路径对比：
 * <ul>
 *   <li>CPython(bare) — 解释执行无标注 .py
 *   <li>mypyc(bare) — 编译零标注 .py（典型场景退化）
 *   <li>mypyc(GCP demand) — GCP 推导标注后 mypyc 编译（本论文目标路径）
 *   <li>mypyc(manual) — 手工标注 oracle 上限
 * </ul>
 * <p>外加 GCP 推导本身的 wall-clock ms 测量。
 *
 * <h3>运行要求</h3>
 * <pre>
 *   - mypyc 已安装（pip install mypy）
 *   - Python 3.10+ 在 PATH 上可用
 *   - 测量耗时较长（数十秒到数分钟）
 * </pre>
 *
 * <h3>如何用本测试的产出回填论文</h3>
 * <ol>
 *   <li>运行 {@code mvn test -Dtest=RQ2_CoreSevenBenchmark};</li>
 *   <li>查看 {@code target/paper-reports/rq2-core7-raw.log} 中的 ASCII 表格；</li>
 *   <li>把表格中的 {@code cpython_ns / mypyc(GCP) ns / speedup} 字段抄入论文 §6.3.1 表 6-2；</li>
 *   <li>提交时把 raw.log 作为补充材料归档。</li>
 * </ol>
 */
@Tag("paper")
public class RQ2_CoreSevenBenchmark {

    private static final String DELEGATE_FQCN = "org.twelve.meridian.python.Table1BenchmarkTest";
    private static final String DELEGATE_METHOD = "produce_table1_table3_with_manual_oracle";
    private static final Path RAW_LOG = Paths.get("target/paper-reports/rq2-core7-raw.log");
    private static final Path EXTRACTED_CSV = Paths.get("target/paper-reports/rq2-core7-extracted.csv");

    @BeforeAll
    static void ensureReportsDir() throws IOException {
        Files.createDirectories(RAW_LOG.getParent());
    }

    @Test
    void rq2_1_invoke_table1_benchmark_and_capture_stdout() throws Exception {
        DelegatingBenchmarkRunner.CaptureResult result =
                DelegatingBenchmarkRunner.runAndCapture(DELEGATE_FQCN, DELEGATE_METHOD, RAW_LOG);

        DelegatingBenchmarkRunner.extractNumericLines(result.capturedStdout(), EXTRACTED_CSV);

        System.out.printf("%n[RQ2.1] Delegate ran in %d ms; raw stdout → %s%n",
                result.elapsedMs(), RAW_LOG);

        // 把 delegate 的失败传递为本测试的失败——但不掩盖原始原因
        if (!result.passed()) {
            fail("Delegate benchmark failed: " + result.failure() +
                 "\n查看 " + RAW_LOG + " 获取完整 stdout");
        }
    }
}
