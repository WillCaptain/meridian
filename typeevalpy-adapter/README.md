# TypeEvalPy adapter scaffold — Meridian

Drop-in for [TypeEvalPy](https://github.com/secure-software-engineering/TypeEvalPy)
`src/target_tools/meridian/`, modeled on `pysonar2` / `headergen`.

## What it does

For each `*.py` under the benchmark tree, run Meridian’s site exporter and write
sibling `*_result.json` in Scalpel / TypeEvalPy shape
(`file`, `line_number`, `col_offset`, `function`|`parameter`|`variable`, `type`).

## Fact inventory (Outline SSOT)

Progress against the **eight-category 513 fact IDs** should use the **latest**
Outline toplas manifest (updated for `toplas-typeevalpy-513`):

```text
../outline/outline/src/test/java/org/twelve/outline/toplas/TYPEEVALPY-FACT-MANIFEST.csv
../outline/outline/src/test/java/org/twelve/outline/toplas/SOTA-PROTOCOL.md
../outline/outline/src/test/java/org/twelve/outline/toplas/REPRODUCE.md
```

Local Meridian progress (no Docker):

```bash
python3 scripts/run-typeevalpy-micro-progress.py \
  --manifest ../outline/outline/src/test/java/org/twelve/outline/toplas/TYPEEVALPY-FACT-MANIFEST.csv
```

**Claim boundary:** that score is Meridian-on-native-Python. It is **not**
Outline-port `FACT_PAIRED` 513/513.

## Install into a TypeEvalPy clone

```bash
cp -R typeevalpy-adapter/src /path/to/TypeEvalPy/src/target_tools/meridian/src
cp typeevalpy-adapter/Dockerfile /path/to/TypeEvalPy/src/target_tools/meridian/
# wire Meridian jar + bin into the image (see Dockerfile comments)
```

Host-side dry run (no Docker):

```bash
python3 typeevalpy-adapter/src/runner.py --bechmark_path /path/to/micro-benchmark
```
