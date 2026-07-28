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

## Files

Each suite writes:

- `<suite>-latest.json` / `<suite>-latest.md` — overwritten every successful run
- `<suite>-<UTC timestamp>.json` / `.md` — immutable snapshot

JSON envelope fields: `suite`, `title`, `product`, `claim_boundary`, `captured_at`, `payload`.

## Regenerate

From repo root (mypyc + CPython required):

```bash
mvn -pl meridian-python -Dtest=Table1BenchmarkTest,TheAlgorithmsBenchmarkTest,ConverterE2ESampleArchiveTest,MiniProjectCompileSampleTest test
# full converter matrix (slow):
mvn -pl meridian-python -Dtest=ConverterE2ETest test
```

Commit updated `*-latest.*` (and optional stamped copies) with the code change that produced them.
