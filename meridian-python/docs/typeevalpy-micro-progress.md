# Meridian × TypeEvalPy micro progress (513 inventory)

> **Not** Outline-port 513/513. Fact IDs from latest Outline toplas manifest;
> inference via Meridian native Python path.

- Manifest: `/Users/imac/Documents/code/github/outline/outline/src/test/java/org/twelve/outline/toplas/TYPEEVALPY-FACT-MANIFEST.csv`
- Outline ref: `toplas-typeevalpy-513 / TYPEEVALPY-FACT-MANIFEST.csv`
- Micro root: `/tmp/typeevalpy-artifacts/artifacts/results-microbenchmark/codestral-v0.1-22b-q&a-prompt/micro-benchmark/python_features`
- **Exact: 335/513 (65.30%)**
- Located (any type): 364
- Elapsed: 160.7s

## By category

| category | exact | total | rate |
|----------|------:|------:|-----:|
| assignments | 64 | 82 | 78.05% |
| classes | 66 | 125 | 52.80% |
| dicts | 86 | 108 | 79.63% |
| direct_calls | 15 | 24 | 62.50% |
| functions | 21 | 37 | 56.76% |
| lambdas | 18 | 34 | 52.94% |
| lists | 45 | 60 | 75.00% |
| returns | 20 | 43 | 46.51% |

## By status

| status | n |
|--------|--:|
| EXACT | 335 |
| MISS_LOCATION | 149 |
| MISS_TYPE | 29 |

## How to re-run

```bash
python3 scripts/run-typeevalpy-micro-progress.py \
  --manifest ../outline/outline/src/test/java/org/twelve/outline/toplas/TYPEEVALPY-FACT-MANIFEST.csv \
  --micro-root "$TYPEEVALPY_MICRO_ROOT"
```

See also `typeevalpy-adapter/` for the Docker harness drop-in (pysonar2-shaped).
