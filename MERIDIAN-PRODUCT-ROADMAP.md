# Meridian Product Roadmap — Python Inferencer

> **Status**: active (2026-07-27)  
> **Repo**: `github/meridian` (`meridian-python`)  
> **Engine**: GCP (`github/gcp`) via ASF  
> **Paper split**: GCP theory = TOPLAS Path A; this product = companion (`gcp-python-cgo`) + shipping tool

## One-sentence product

**Meridian** is a deterministic zero-annotation Python type inferencer built on GCP: unannotated source → PEP 484 annotations → mypyc → faster native code. TypeEvalPy harness scores are a **measurable outcome**, not the product definition.

## Why this is meaningful

| Pillar | Role |
|--------|------|
| GCP | Shared inference framework (already SOTA on Outline-port TypeEvalPy 513/513) |
| Meridian | Real Python carrier + shippable inferencer |
| IDE | Daily developer value (diagnostics / hover / annotate-on-save) |
| mypyc | System value: same program faster than unannotated CPython |
| TypeEvalPy | External proof on unmodified Python (micro first; Autogen later) |

Claim boundary (do not blur):

- Outline **513/513** ≠ Autogen README #1.
- Native-Python harness numbers belong with Meridian / companion paper, not as a rewrite of the GCP TOPLAS empirics.

## Architecture (already in tree)

```text
unannotated .py
  → PythonAstBridge (py_ast_dump.py)
  → PythonGCPConverter (+ PyConverter registry)
  → ASF.infer()  [GCP]
  → TypeAnnotationGenerator / PythonAnnotationWriter / FunctionSpecializer
annotated .py / .pyi
  → MypycRunner
native extension + speedup vs unannotated baseline
```

Entry: `PythonInferencer`. Design detail: `meridian-python/spec/conversion-architecture.md`.  
Feature backlog (P0–P11): `meridian-python/spec/plan.md` (mostly converter coverage ✅).

## Phased plan

### Phase 0 — Stabilize & brand (now → ~1 week)

**Goal:** Meridian is a named, runnable product surface, not only a Maven module.

| # | Work | Done when |
|---|------|-----------|
| 0.1 | Public name + one-liner in root `README.md` | ✅ README sells the product |
| 0.2 | CLI: `meridian infer path.py` → annotated source / stubs | ✅ `bin/meridian` + `MeridianCli` (JSON sites → Phase 1.2) |
| 0.3 | Pin sibling deps (`gcp`, `msll`) + `REPRODUCE.md` | ✅ `REPRODUCE.md` |
| 0.4 | Do **not** change frozen Outline toplas / GCP core for branding | ✅ guardrail |

### Phase 1 — Ship the inferencer MVP (~2–3 weeks)

**Goal:** Developers can install/run Meridian and get annotations.

| # | Work | Done when |
|---|------|-----------|
| 1.1 | Annotation quality gate on supported subset (see feature coverage doc) | CI tests green |
| 1.2 | Site export (FR/FP/LV + file/line/col + Python type vocabulary) | ✅ `TypeEvalPySiteExporter` + `meridian sites` |
| 1.3 | Location fidelity: bridge `_line`/`_col` into GCP tokens | ✅ Name/arg/def/assign tokens carry line/col (GT cols = py+1) |
| 1.4 | Package story (fat jar or wrapper script; pip later OK) | ✅ `bin/meridian` (fat jar / pip later) |

**Success metric:** annotate a small real package; mypyc builds; speedup vs **unannotated** CPython baseline (existing E2E style).

### Phase 2 — TypeEvalPy micro harness (outcome / SOTA path) (~2–4 weeks after 1.2)

**Goal:** Official Docker harness score on micro-benchmark (unmodified Python).

| # | Work | Done when |
|---|------|-----------|
| 2.1 | `TypeEvalPy/src/target_tools/meridian` (template: `pysonar2`) | ✅ scaffold in `typeevalpy-adapter/` |
| 2.2 | Map Meridian sites → `*_result.json` | ✅ via `meridian sites` / adapter runner |
| 2.3 | Eight categories vs Outline **latest** 513-ID manifest | ✅ progress **335/513** (location + update/lists/call-return adapters) |
| 2.4 | Expand toward full 18 categories as coverage allows | competitive vs HeaderGen/Jedi |

**Success metric:** reproducible micro exact-match number under TypeEvalPy’s protocol.  
**Not yet:** Autogen README #1 (separate, harder race against LLM totals ~74979/78373).

### Phase 3 — VS Code / LSP (~after Phase 1 API stable)

**Goal:** In-editor type feedback without forcing full annotate+compile.

| # | Work | Done when |
|---|------|-----------|
| 3.1 | Language server (or thin VS Code extension calling Meridian) | hover / diagnose on open file |
| 3.2 | Commands: Infer / Annotate buffer / Annotate workspace | UX loop usable |
| 3.3 | Don’t block Phase 2 on the extension | harness can ship without UI |

### Phase 4 — mypyc performance productization (companion empirics)

**Goal:** Documented speedups: unannotated CPython vs Meridian-annotated mypyc.

| # | Work | Done when |
|---|------|-----------|
| 4.1 | Benchmark suite (microkernels + 1–2 real libs) — see plan.md P8 | tables for companion paper |
| 4.2 | Failure modes: when annotation is too weak for mypyc | documented subset |
| 4.3 | Paper `gcp-python-cgo` / release notes | cite Meridian CLI + numbers |

Baseline is **non-annotated Python** (CPython), not “empty .pyc”. Optional secondary baseline: mypyc on bare (no Meridian) annotations if any.

### Phase 5 — Autogen stretch (optional, months)

Only after Phase 2 is solid: scale exporter + timeouts + remaining Python-only features. Treat README leaderboard #1 as a **KPI**, not a gate for shipping Meridian or accepting GCP TOPLAS.

## Relation to existing docs

| Doc | Role after this roadmap |
|-----|-------------------------|
| `spec/plan.md` | Converter / coverage backlog (keep updating ✅/⬜) |
| `spec/conversion-architecture.md` | Pipeline internals |
| `docs/python-feature-coverage.md` | What syntax we support |
| **this file** | Product + release + SOTA outcome sequencing |

## Immediate next actions (resume here)

1. ~~Phase 0–1~~ — CLI + sites + line/col.
2. ~~Phase 2 progress harness~~ — TypeEvalPy micro strict/compat **513/513**
   (`meridian-python/docs/typeevalpy-micro-progress.{md,json}`).
3. **Active — Phase 4 mypyc productization** (Meridian result → compile, not raw GCP):
   - `meridian compile` + `CompilePipeline` (annotate / specialize / mypyc / optional bench)
   - Multi-concrete Outline call-site bindings → dispatcher + clones (any types)
   - Plan: `meridian-python/docs/mypyc-compile-and-ide-plan.md` (IDE parked)
4. Keep Outline toplas **read-only** for Meridian demos (do not edit frozen 513 claim).
5. Later: Phase 3 IDE/LSP; optional TypeEvalPy Docker adapter polish.

### Outline reference (keep current)

Always prefer the **checked-out latest** Outline toplas tree — not older 405-era docs:

| Artifact | Path (sibling `../outline`) |
|----------|-----------------------------|
| Fact ID SSOT (513) | `outline/src/test/java/org/twelve/outline/toplas/TYPEEVALPY-FACT-MANIFEST.csv` |
| Protocol / claim boundary | `.../toplas/SOTA-PROTOCOL.md` |
| Reproduce Outline-port | `.../toplas/REPRODUCE.md` |
| Scoring vocabulary (Outline side) | `.../toplas/common/TypeEvalPyFactSupport.java` |
| Release tag | `toplas-typeevalpy-513` |

Meridian reuses **IDs + FR/FP/LV + Python vocab intent**; it does **not** reuse Outline-port PASS counts.

### Note on “hidden adapters”

Grammar mapping alone will not hit every soaps fact. Meridian may need carrier-side adapters (stdlib models, call-site shims, location/name normalization) — analogous in *role* to Outline PORTABLE/ADAPTED, but implemented on the Python→GCP path and scored under the native/harness metric.

## Guardrails

- Do not invalidate Outline TypeEvalPy 513/513 by drive-by GCP core edits for Meridian demos.
- Public SOTA text must state harness vs Outline-port scope.
- Conversation with the team in Chinese; public artefacts in English.
