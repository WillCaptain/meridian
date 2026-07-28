# Meridian Plan — mypyc compile focus + parked IDE

> Status: planning (2026-07-28)  
> Active focus: Meridian annotate → mypyc → performance  
> Parked: IDE / LSP realtime typecheck

## Product stance

We are not aiming to “support all of Python”. We extract as much reliable type
information as we can, annotate only when sound for mypyc, and leave gaps
unannotated rather than inventing types.

---

## Part A — IDE support (parked; do later)

Goal: realtime developer feedback without forcing full annotate+compile.

| # | Work | Done when |
|---|------|-----------|
| I1 | Buffer API over `PythonInferenceResult` (source string + optional usage) | in-memory hover facts |
| I2 | Thin LSP (or VS Code extension calling Meridian) | hover + `publishDiagnostics` |
| I3 | Map constraint conflicts → diagnostics (not only inferred types) | clear error ranges |
| I4 | Cache / debounce / cancel stale jobs | interactive latency |
| I5 | Real-project smoke (cross-module, false-positive budget) | usable daily loop |

**Guardrail:** do not block mypyc compile productization on IDE. CLI + E2E remain
the proof surface until Part A is resumed.

---

## Part B — Active focus: Meridian + mypyc compilation

### Target user flow

```text
a) meridian compile
   naked .py (+ optional usage) → Meridian annotate/specialize → mypyc
        ↓
b) check eval result vs native
   Meridian(.so) return value == CPython(naked) return value
        ↓
c) check eval performance vs native
   speedup_vs_native = native_ns / meridian_ns
   (optional control: mypyc bare)
```

CLI:
```bash
meridian compile lib.py --calls-inline 'sum_range(1000)' \
  --bench '[["sum_range",[1000],20000]]' -o /tmp/out
```

### Current baseline (already in tree)

| Piece | Status |
|-------|--------|
| `infer` / `stub` / `sites` CLI | ✅ |
| `inferWithContextDetailed` + annotate | ✅ (tests) |
| `MypycRunner.compile` / `inferAndCompile` | ✅ API; ❌ no product CLI |
| `ConverterE2ETest` + `generic_benchmark.py` | ✅ speedup gates |
| `FunctionSpecializer` monomorphization | ✅ clones; ❌ no call-site rewrite / CLI |
| Annotation first-wins on conflicting call sites | ⚠️ wrong for multi-type `f` |

### Work packages

#### B1. Productize the compile pipeline (CLI)

```bash
meridian compile path.py \
  [--calls usage.py | --calls-inline "..."] \
  [--specialize] \
  [--annotate-all] \
  [-o out_dir] \
  [--bench cases.json]
```

Steps inside `compile`:

1. Load naked library (+ optional usage).
2. Infer (`inferFileDetailed` or `inferWithContextDetailed`).
3. Annotate:
   - default `SAFE_PARTIAL`
   - if `--specialize` or multi-type call sites detected → `FunctionSpecializer`
4. Run mypyc on annotated outputs (multi-file when imports need native helpers).
5. Optional `--bench`: run `generic_benchmark.py` and print speedup table.

Acceptance: one command covers a→d on a fixture that today only lives in JUnit.

#### B2. Benchmark-gated annotation quality

Keep / extend existing gates rather than inventing new scoreboards:

| Gate | Role |
|------|------|
| `ConverterE2ETest#*_gives_speedup` | annotate quality → mypyc wins |
| `cross_module_inference_gives_speedup` | multi-module native edge |
| `listcomp_gives_speedup` | annotation must compile |
| `MypyStrictGateTest` | annotated fixtures pass `mypy --strict` |
| `MonomorphizationTest` | specialization correctness + speedup |

Rule: every new annotate/specialize change either improves a gate or adds a
deterministic compile/assert before any speedup assert.

#### B3. Wire specialization into the main path

Today specialization is a side path (`MonomorphizationTest` only).

Do:

1. Detect multi-type call-site tuples after context infer.
2. Prefer `FunctionSpecializer` over `AnnotationWriter` first-wins merge for those funcs.
3. Emit primary + `_name_typesig` clones with concrete annotations.
4. Rewrite usage call sites to the matching specialized name **or** emit a thin
   typed dispatcher that mypyc can still optimize when types are static.

Acceptance: any program with multiple concrete call-site bindings for the same
function compiles and runs under mypyc (see Part C; `str`/`int` is one fixture).

#### B4. Performance check protocol

Always report three columns:

1. CPython(naked)
2. mypyc(bare, no Meridian types) — control
3. mypyc(Meridian annotated / specialized)

Use isolated dependency lanes (already fixed in `generic_benchmark.py`) so
helper `.so` files cannot inflate the CPython baseline.

---

## Part C — Outline optional / parametric call-site bindings (general)

`str`/`int` is **only an example**. The same rule applies to **every** distinct
concrete type tuple Outline/GCP binds at call sites (int/float, list variants,
str/bytes, …).

### Example (Outline)

```text
let f = x -> x + x;
f("string");
f(100);
f(1.5);
```

GCP keeps `f` parametric; each call site supplies a concrete binding.
Python/mypyc need **concrete** annotations, so one naked `def f(x)` cannot
honestly carry all of those bindings at once.

### Policy (type-agnostic)

| Situation | Action |
|-----------|--------|
| All call sites share one concrete type tuple | Annotate original in place |
| Multiple concrete type tuples (any types) | Monomorphize: `isinstance` dispatcher at `f` + `_f_<typesig>` clones |
| Arg outline not concrete / not isinstance-erasable | Ignore that call site for specialization |
| No usage / no evidence | Leave naked (SAFE_PARTIAL) |
| Never | First-wins merge of conflicting concrete bindings |

### Engineering

1. Lift planned `name = lambda ...` to `def`.
2. For each concrete tuple → annotated clone `_name_<sig>`.
3. Replace original with dispatcher over **all** tuples (not a str/int special case).
4. Regression: multi-type fixture (str+int and/or int+float) must run correctly
   under mypyc; correctness before speedup.

---

## Sequencing

```text
Done / in tree
  B1 CLI compile skeleton (annotate + mypyc + optional bench) ✅
  B3 specialize + isinstance dispatcher (any multi-concrete tuples) ✅
  C  poly fixtures (str/int + int/float) ✅
  Compile uses Meridian annotated output (GCP is infer kernel only) ✅

Active
  B2 keep ConverterE2E / Monomorphization gates green; expand compile benches

Later (parked)
  Part A IDE/LSP
```

## Success metrics

1. ~~`meridian compile` runs a→d~~ ✅ CLI + `CompilePipeline`
2. ~~Multi-concrete call-site fixture compiles and runs~~ ✅ `PolyOptionalCallSitesTest`
3. Existing speedup gates do not regress; poly case has correctness gate even
   if speedup is modest.
4. IDE remains documented only until Part A is explicitly resumed.
