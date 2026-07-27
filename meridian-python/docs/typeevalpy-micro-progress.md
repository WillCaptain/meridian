# Meridian × TypeEvalPy micro progress (strict)

> **Not** Outline-port 513/513. Fact IDs from latest Outline toplas manifest;
> inference via Meridian native Python path (GCP + Python harness).
> Primary gate is **strict** exact `(file,line,col,kind,symbol)` pairing. Compat soft-match is reported alongside and never uses expected types.

- Manifest: `/Users/imac/Documents/code/github/outline/outline/src/test/java/org/twelve/outline/toplas/TYPEEVALPY-FACT-MANIFEST.csv`
- Outline ref: `toplas-typeevalpy-513 / TYPEEVALPY-FACT-MANIFEST.csv`
- Micro root: `/tmp/typeevalpy-artifacts/artifacts/results-microbenchmark/codestral-v0.1-22b-q&a-prompt/micro-benchmark/python_features`
- Scoring mode: `strict`
- **Exact: 513/513 (100.00%)**
- Located (any type): 513
- Elapsed: 164.5s

## Modes

| mode | exact | rate | located | uses expected types? |
|------|------:|-----:|--------:|:--------------------:|
| strict | 513/513 | 100.00% | 513 | no |
| compat | 513/513 | 100.00% | 513 | no |

## By category

| category | exact | total | rate |
|----------|------:|------:|-----:|
| assignments | 82 | 82 | 100.00% |
| classes | 125 | 125 | 100.00% |
| dicts | 108 | 108 | 100.00% |
| direct_calls | 24 | 24 | 100.00% |
| functions | 37 | 37 | 100.00% |
| lambdas | 34 | 34 | 100.00% |
| lists | 60 | 60 | 100.00% |
| returns | 43 | 43 | 100.00% |

## By status

| status | n |
|--------|--:|
| EXACT | 513 |

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
