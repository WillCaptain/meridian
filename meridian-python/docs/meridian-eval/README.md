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
| `l3-more-itertools` | `ProductionCorpusL3ProofTest` | L3 multi-module import graph (facade→recipes→numeric) |
| `l4-more-itertools-upstream` | `ProductionCorpusL4ProofTest` | L4 upstream more-itertools recipe bodies (selected) |
| `l5-more-itertools-pkg` | `ProductionCorpusL5ProofTest` | L5 nearly-full recipes + selected more.py package surface |
| `l6-more-itertools-full` | `ProductionCorpusL6ProofTest` | L6 full more.py coverage + annotated hot path |

## Files

Each suite writes:

- `<suite>-latest.json` / `<suite>-latest.md` — overwritten every successful run
- `<suite>-<UTC timestamp>.json` / `.md` — immutable snapshot

JSON envelope fields: `suite`, `title`, `product`, `claim_boundary`, `captured_at`, `payload`.

## Regenerate

From repo root (mypyc + CPython required):

```bash
mvn -pl meridian-python -Dtest=Table1BenchmarkTest,TheAlgorithmsBenchmarkTest,ConverterE2ESampleArchiveTest,MiniProjectCompileSampleTest,ProductionCorpusProofTest,ProductionCorpusL3ProofTest,ProductionCorpusL4ProofTest,ProductionCorpusL5ProofTest,ProductionCorpusL6ProofTest test
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
| L3 | Multi-module import graph (`l3-more-itertools`) | `ProductionCorpusL3ProofTest` |
| L4 | Upstream recipe bodies (`l4-more-itertools-upstream`) | `ProductionCorpusL4ProofTest` |
| L5 | Package surface (`l5-more-itertools-pkg`) | `ProductionCorpusL5ProofTest` |
| L6 | Full more.py coverage + annotated hot path (`l6-more-itertools-full`) | `ProductionCorpusL6ProofTest` |
| L7 | Real production app hot paths | not yet |

L2 claim boundary: **selected pure-Python recipes with usage snippets**, not a pip-installed whole package.

L3 claim boundary: **facade → recipes → numeric** import graph with adapted list hot paths; not the full upstream `more.py`.

L4 claim boundary: **upstream more-itertools v10.7.0 algorithm bodies (selected)**; may strip known-wrong `-> list[…]` returns before mypyc; not full `more.py`.

L5 claim boundary: **nearly full recipes.py + selected more.py** as a multi-module package; primary facade keeps Meridian params; large deps are annotation-stripped before mypyc; not the entire `more.py`.

L6 claim boundary: **nearly-full more.py function surface for coverage**; mypyc on hot subgraph with Meridian param annotations kept on `mi_hot` (`keep_deps`); larger inputs; not every class/thread helper.
