# Paper Experiments — Meridian / mypyc-GCP (RQ2 + 实验 E)

本目录承载博士论文第 6 章 **RQ2**（应用三：Python AOT 性能优化）与**实验 E**（GCP vs pytype/pyright/mypy 横向对比）的实验代码与数据。

> 对应论文位置：
> - 第 6 章 §6.3 RQ2：应用三的实验验证——GCP-Python 零标注 AOT 编译性能；
> - 第 6 章 §6.6 / 表 6-10：实验 E——四类主流类型推导方法的对比矩阵。

## 实验组成

| 类 | RQ / 表 | 测量目标 | 期望结果（论文 §6.3 已陈述） |
|---|---|---|---|
| `RQ2_CoreSevenBenchmark` | 表 6-2 | 核心 7 函数 oracle 基准（ARM64） | GCP 13.17× CPython 算术平均、GCP/manual = 101.8% |
| `RQ2_TwentyTwoCategoryBenchmark` | 表 6-3 | 22 类别 60+ 函数扩展基准 | 算术平均加速 17.1×、峰值 120.4× |
| `RQ2_AnnotationStrategyComparison` | 表 6-4 | 无标注 / GCP / 部分手工 / 完全手工 对比 | GCP 自动 13.17× / 标注耗时 66 ms |
| `RQ2_CrossPlatformConsistency` | 表 6-5 | ARM64 vs x86-64 一致性 | x86-64 达到 ARM64 性能的 98.4%、GCP/manual = 99.7% |
| `RQ2_TheAlgorithmsRealCode` | 表 6-6 | TheAlgorithms/Python 真实代码 | 18 函数算术平均 18.51×、GCP/manual = 99.9% |
| `RQ2_AnnotationCoverage` | 表 6-7 | 标注覆盖率（在 78 函数测试集上） | 参数 97.4% / 返回 100% / 平均推导 8.5 ms / 函数 |
| `ExperimentE_HorizontalComparison` | 表 6-10 | GCP vs pytype / pyright / mypy 推断模式 | GCP 在零标注下的函数签名推导成功率高于其它三者 |

## 既有测试的复用

本模块已经有大量基准测试，本 `paper/` 目录的核心定位是**把既有测试对应到论文表格**，并为每个表格提供：
- 一个对论文断言的 JUnit `assertThat` 包装；
- 一个 CSV 报告输出，可直接回填论文；
- README 中明确"该 paper 类调用哪个既有基础测试"。

既有测试与 paper 类的映射关系：

| Paper 类（本目录） | 既有基础测试（同模块） |
|---|---|
| `RQ2_CoreSevenBenchmark` | `MypycBenchmarkTest`、`Table1BenchmarkTest` |
| `RQ2_TwentyTwoCategoryBenchmark` | `MypycBenchmarkTest`（扩展用例） |
| `RQ2_AnnotationStrategyComparison` | `AnnotationWriterTest` + 上述 |
| `RQ2_CrossPlatformConsistency` | （新增）需在 ARM64 + x86-64 两套硬件上跑同套基准 |
| `RQ2_TheAlgorithmsRealCode` | `TheAlgorithmsBenchmarkTest` |
| `RQ2_AnnotationCoverage` | `PythonInferencerTest` + 78 函数综合测试集 |
| `ExperimentE_HorizontalComparison` | （新增）需调用 pytype / pyright / mypy 外部命令 |

## 数据集

```
src/test/resources/paper/
├── benchmarks/
│   ├── core7/                # 表 6-2 的 7 个 oracle 函数 + 手工标注 oracle .py
│   ├── 22-categories/        # 表 6-3 的 22 类别 × 60+ 函数
│   └── benchmark-runner.py   # 计时脚本（≥ 500k 次迭代取中位数）
├── thealgorithms/
│   ├── number-theory/        # 7 个数论函数（逐字提取自 TheAlgorithms/Python）
│   ├── sorting/              # 6 个排序算法
│   └── searching/            # 5 个搜索算法
└── horizontal/
    ├── 78-function-suite.py  # 实验 E 测试集
    └── expected-types.json   # 手工标注 ground truth（评判各工具推导是否正确）
```

## 如何运行

```bash
# 单独运行 RQ2
mvn test -Dtest='paper.RQ2_*' -DfailIfNoTests=false

# 单独运行实验 E（需先安装 pytype / pyright / mypy）
pip install pytype pyright mypy
mvn test -Dtest=paper.ExperimentE_HorizontalComparison
```

外部依赖：

| 工具 | 用途 | 安装 |
|---|---|---|
| `mypyc` | 表 6-2/3/4/5/6 AOT 编译 | `pip install mypy` |
| `pytype` | 实验 E 横向对比 | `pip install pytype` |
| `pyright` | 实验 E 横向对比 | `pip install pyright` |
| `numba` | 表 6-2 JIT 基线（可选） | `pip install numba` |

## 输出报告

```
target/paper-reports/
├── rq2-core7-benchmark.csv          # 表 6-2 重生成
├── rq2-22-categories-benchmark.csv  # 表 6-3 重生成
├── rq2-annotation-strategy.csv      # 表 6-4 重生成
├── rq2-cross-platform.csv           # 表 6-5 重生成
├── rq2-thealgorithms.csv            # 表 6-6 重生成
├── rq2-annotation-coverage.csv      # 表 6-7 重生成
├── rq2-summary.json                  # 论文回填用结构化数据
└── experimentE-horizontal.csv       # 表 6-10 GCP vs pytype/pyright/mypy
```

## 答辩重现指南

```bash
git clone https://github.com/WillCaptain/meridian.git
cd meridian/meridian-python
git checkout <thesis-final-commit>
mvn clean test -Dtest='paper.RQ2_*' -DfailIfNoTests=false
mvn test -Dtest=paper.ExperimentE_HorizontalComparison
cat target/paper-reports/rq2-summary.json
```

期望看到与论文 §6.3 表 6-2 到 6-7、§6.6 表 6-10 一致的数据（容差 ±5%——性能基准对 CPU 调度敏感）。

## 已知边界

- **跨平台一致性**（表 6-5）要求两套硬件实测——CI 通常只能跑一套，跨平台数据由人工触发两轮汇总。
- **NumPy / PyTorch 等 C 扩展密集库**未纳入 GCP-Python 测试范围——这一限制在论文 §6.6 已声明。
- **实验 E** 对 pytype / pyright 的版本敏感——使用 `requirements-paper.txt` 锁定版本。
