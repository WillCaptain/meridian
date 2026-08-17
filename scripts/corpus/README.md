# Real-world corpus harness — Meridian vs classical tools & LLM

This directory is the supplementary harness for the paper's §8.7 real-world
corpus evaluation. It defines (1) the corpus pick, (2) the fact-manifest shape,
and (3) the runners that score Meridian (native) and the baselines against the
same hand-audited fact IDs. It is **oracle-free**: no tool's locator alignment
ever consults a tool's or the benchmark's expected types; the only ground truth
used is the final predicted-vs-expected comparison.

## 1. Corpus pick (`corpus-manifest.csv`)

Pinned, unmodified, duck-typed Python packages. Columns:

| column | meaning |
|---|---|
| `project` | corpus key (directory name under `--corpus-root`) |
| `release` | pinned tag |
| `url` | upstream repo |
| `license` | SPDX short id |
| `inference_stress` | the property that makes it a stress case |

Check out each project at its pinned release under a single corpus root, e.g.:

```bash
git clone --depth 1 --branch v10.7.0 https://github.com/more-itertools/more-itertools "$CORPUS_ROOT/more-itertools"
```

Sources are used as-is; no adaptation, no port.

## 2. Fact-manifest shape (`fact-manifest.csv`)

The fact manifest is the single source of truth for what every tool must answer.
Header (see `fact-manifest.template.csv`):

```
fact_id,project,release,file,line,column,kind,symbol,expected_types,category,source
```

| column | meaning |
|---|---|
| `fact_id` | stable per-project index, e.g. `requests/001` |
| `project` | corpus key |
| `release` | pinned tag |
| `file` | repo-relative path, e.g. `src/requests/models.py` |
| `line` | 1-based line |
| `column` | 0-based column offset (TypeEvalPy/Scalpel convention) |
| `kind` | `FR` (function return) / `FP` (parameter) / `LV` (local binding) |
| `symbol` | function / parameter / variable name |
| `expected_types` | `|`-separated closed-world Python type set, e.g. `str|None` |
| `category` | `assignment` / `call` / `return` / `param` / `attribute` |
| `source` | `hand-audited` or `sampled:seed=<n>` |

Rules:

- Sites are drawn from **public API surfaces** (module-level functions and their
  signatures) so a reader can audit the expected type from source.
- The manifest is authored **before any tool is run** and independently of any
  tool's output. Record the sampling seed in `source`.
- `expected_types` uses the same closed-world Python vocabulary and `standardize`
  alias map as the TypeEvalPy micro harness (see `scripts/run-typeevalpy-micro-progress.py`).

## 3. Scoring (oracle-free)

- Meridian: **strict** `(file, line, col, kind, symbol)` key match on its `sites`
  output. No expected-type disambiguation at locator time.
- Pyright: `(file, line, col)` hover type via LSP, kind/symbol resolved from the
  fact's own locator; predicted set compared to `expected_types`.
- Type4Py / HiTyper: name-keyed predictions mapped to the fact's `symbol` within
  the fact's `file`; predicted set compared to `expected_types`.
- LLM: per-function prompt, same locator question; predicted set compared to
  `expected_types`.

A tool that crashes, times out, or yields nothing at a site scores `MISS_LOCATION`;
the denominator is always the full manifest.

## 4. Runners

```bash
# Meridian native (oracle-free strict)
python3 scripts/corpus/run-meridian-corpus.py \
  --manifest fact-manifest.csv --corpus-root "$CORPUS_ROOT" --out meridian.json

# Baselines (Pyright LSP hover, Type4Py, HiTyper)
python3 scripts/corpus/run-baselines.py \
  --manifest fact-manifest.csv --corpus-root "$CORPUS_ROOT" --tools pyright,type4py,hityper --out baselines.json
```

Both runners emit a per-fact status matrix (`EXACT` / `MISS_TYPE` / `MISS_LOCATION`
/ `ERROR`) keyed by `fact_id`, plus the exact-rate summary used to fill §8.7.

## 5. Claim boundary

This corpus is a hand-audited **sample**, not exhaustive. It is reported as a
transfer signal beyond the TypeEvalPy micro-benchmark, not as a second
closed-domain completeness claim. It shares the engine, not the Outline port,
with the §8.2 result; it is the first evaluation in the paper with no Outline
port and no TypeEvalPy provenance.
