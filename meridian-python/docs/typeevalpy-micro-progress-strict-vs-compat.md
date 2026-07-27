# TypeEvalPy micro: strict vs compat

| mode | exact | rate | located | uses expected types? |
|------|------:|-----:|--------:|:--------------------:|
| strict | 513/513 | 100.00% | 513 | no |
| compat | 513/513 | 100.00% | 513 | no |

Legacy oracle-assisted 513/513 archived at
`typeevalpy-micro-progress-legacy-oracle-assisted-513.json` (not SOTA).

Meridian native-Python micro progress against Outline fact_id inventory. GCP + Python harness. Primary gate is strict exact-locator pairing; compat allows locator soft-match but never uses ground-truth types to pick sites. legacy mode (expected-type disambiguation) is historical only and is not SOTA. Not Outline-port FACT_PAIRED 513/513; not Autogen README #1.
