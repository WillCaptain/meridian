# Meridian product evaluation archive

Artifacts for a future **Meridian → dynamic languages / mypyc** paper.
**Not** a GCP core / TOPLAS / Outline-port SOTA claim.

## Suites

| Suite id | Source test | Contents |
|----------|-------------|----------|
| `table1-math-utils` | `Table1BenchmarkTest` | math_utils four-way (CPython / bare / Meridian / manual) |
| `thealgorithms-maths` | `TheAlgorithmsBenchmarkTest` | stripped TheAlgorithms maths funcs |
| `converter-e2e` | `ConverterE2ETest` (AfterAll) | per-converter avg bare× / Meridian× |
| `converter-e2e-sample` | `ConverterE2ESampleArchiveTest` | curated subset for quicker paper refresh |
| `mini-project-sample` | `MiniProjectCompileSampleTest` | real-shaped `--calls` prune/rewrite gate |
| `l2-more-itertools` | `ProductionCorpusProofTest` | L2 third-party-shaped recipes: coverage → compile → correctness → speedup |

## Files

Each suite writes:

- `<suite>-latest.json` / `<suite>-latest.md` — overwritten every successful run
- `<suite>-<UTC timestamp>.json` / `.md` — immutable snapshot

JSON envelope fields: `suite`, `title`, `product`, `claim_boundary`, `captured_at`, `payload`.

## Regenerate

From repo root (mypyc + CPython required):

```bash
mvn -pl meridian-python -Dtest=Table1BenchmarkTest,TheAlgorithmsBenchmarkTest,ConverterE2ESampleArchiveTest,MiniProjectCompileSampleTest,ProductionCorpusProofTest test
# full converter matrix (slow):
mvn -pl meridian-python -Dtest=ConverterE2ETest test
# CLI four-gate scan (optional --archive writes docs/meridian-eval/):
# java -cp ... org.twelve.meridian.python.cli.MeridianCli corpus \
#   meridian-python/src/test/resources/production_corpus/l2_more_itertools --archive
```

Commit updated `*-latest.*` (and optional stamped copies) with the code change that produced them.

## Proof ladder

| Level | What | Status |
|-------|------|--------|
| L0 | Playground micro demos | online at 12th.ai/playground/python |
| L1 | TheAlgorithms / table1 / converters | archived here |
| L2 | Third-party-shaped subset (`l2-more-itertools`) | `ProductionCorpusProofTest` |
| L3+ | Full package import graphs / production hot paths | not yet |

L2 claim boundary: **selected pure-Python recipes with usage snippets**, not a pip-installed whole package.
