# Meridian × TypeEvalPy micro progress (strict)

> **Not** Outline-port 513/513. Fact IDs from latest Outline toplas manifest;
> inference via Meridian native Python path (GCP + Python harness).
> Primary gate is **strict** exact `(file,line,col,kind,symbol)` pairing. Compat soft-match is reported alongside and never uses expected types.

- Manifest: `/Users/imac/Documents/code/github/outline/outline/src/test/java/org/twelve/outline/toplas/TYPEEVALPY-FACT-MANIFEST.csv`
- Outline ref: `toplas-typeevalpy-513 / TYPEEVALPY-FACT-MANIFEST.csv`
- Micro root: `/tmp/typeevalpy-artifacts/artifacts/results-microbenchmark/codestral-v0.1-22b-q&a-prompt/micro-benchmark/python_features`
- Scoring mode: `strict`
- **Exact: 504/513 (98.25%)**
- Located (any type): 511
- Elapsed: 167.7s

## Modes

| mode | exact | rate | located | uses expected types? |
|------|------:|-----:|--------:|:--------------------:|
| strict | 504/513 | 98.25% | 511 | no |
| compat | 505/513 | 98.44% | 513 | no |

## By category

| category | exact | total | rate |
|----------|------:|------:|-----:|
| assignments | 81 | 82 | 98.78% |
| classes | 124 | 125 | 99.20% |
| dicts | 107 | 108 | 99.07% |
| direct_calls | 21 | 24 | 87.50% |
| functions | 34 | 37 | 91.89% |
| lambdas | 34 | 34 | 100.00% |
| lists | 60 | 60 | 100.00% |
| returns | 43 | 43 | 100.00% |

## By status

| status | n |
|--------|--:|
| EXACT | 504 |
| MISS_LOCATION | 2 |
| MISS_TYPE | 7 |

## How to re-run

```bash
python3 scripts/run-typeevalpy-micro-progress.py \
  --mode both \
  --manifest ../outline/outline/src/test/java/org/twelve/outline/toplas/TYPEEVALPY-FACT-MANIFEST.csv \
  --micro-root "$TYPEEVALPY_MICRO_ROOT"
```

See also `typeevalpy-adapter/` for the Docker harness drop-in (pysonar2-shaped).

Historical oracle-assisted 513/513 (not a SOTA claim):
`docs/typeevalpy-micro-progress-legacy-oracle-assisted-513.json`.
