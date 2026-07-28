# Meridian Plan — mypyc compile focus + parked IDE

> Status: active (2026-07-28)  
> Active focus: Meridian annotate → specialize → tree-shake → mypyc → performance  
> Parked: IDE / LSP shell (API surface exists; no LSP host yet)

## Product stance

We are not aiming to “support all of Python”. We extract as much reliable type
information as we can. **IDE** and **compile** consume the same inference with
different policies.

---

## Dual surfaces (IDE vs compile)

Same `infer(lib [, usage])`, two consumers:

```text
infer(lib [, usage])
  ├─ IDE surface  (IdeTypeSurface)     — hover / diagnostics (parked host)
  │     keep Union / Optional / wide Outline bindings
  │
  └─ Compile surface (CompilePipeline) — mypyc input only
        monomorphize from concrete call-site tuples
        tree-shake defs never reached from usage
```

### IDE surface

| Rule | Detail |
|------|--------|
| Keep Union / Optional | Definition-width types are useful for hover (`x: int \| str`, `Optional[T]`) |
| No AnnotationPolicy drop | Do not apply `SAFE_PARTIAL` / compile narrowing |
| Param display | Use full `outlineToTypeStr` (not compile’s `outlineToTypeStrForParam`) |
| API | `IdeTypeSurface.hoverTypes(lib, usage?)` → `func#param` / `func#return` map |
| Host | LSP / editor still parked (I1–I5); call the API when resumed |

### Compile surface

| Situation | Action |
|-----------|--------|
| Only `f(10)` | Emit concrete `f(x: int) -> …` (or `_f_int` under poly dispatch) |
| Also `f("str")` | Clone per concrete tuple + `isinstance` dispatcher at `f` |
| Function never called from usage | **Remove from mypyc input** (`CompileSourcePruner`) |
| Called only as callee of a kept function | Keep (reachability closure) |
| No usage / no evidence | Do **not** tree-shake; annotate whole module with `SAFE_PARTIAL` |
| Never | Feed definition-width `Union` to mypyc as a substitute for monomorphization |

Example (Outline spirit):

```text
let f = x -> x + 1;     # IDE may show x: String|Number
f(10);                  # compile → f(x: int) / _f_int
f("str");               # compile → additional _f_str (+ dispatcher)
# g never called        # compile → drop g from .py fed to mypyc
```

Tree-shake deletes from the **compile artifact**, not from the user’s project
sources. Incomplete usage can over-prune; callers should pass entry/usage that
covers the hot path.

---

## Part A — IDE support (parked host; API ready)

Goal: realtime developer feedback without forcing full annotate+compile.

| # | Work | Done when |
|---|------|-----------|
| I0 | `IdeTypeSurface` (full Union/Optional hover map) | ✅ API in tree |
| I1 | Buffer API over `PythonInferenceResult` + `IdeTypeSurface` | in-memory hover facts |
| I2 | Thin LSP (or VS Code extension calling Meridian) | hover + `publishDiagnostics` |
| I3 | Map constraint conflicts → diagnostics (not only inferred types) | clear error ranges |
| I4 | Cache / debounce / cancel stale jobs | interactive latency |
| I5 | Real-project smoke (cross-module, false-positive budget) | usable daily loop |

**Guardrail:** do not block mypyc compile productization on IDE host work.

---

## Part B — Active focus: Meridian + mypyc compilation

### Target user flow

```text
a) meridian compile
   naked .py (+ usage) → annotate/specialize → prune unreachable → mypyc
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
| `inferWithContextDetailed` + annotate | ✅ |
| `CompilePipeline` + `meridian compile` | ✅ |
| `FunctionSpecializer` monomorphization | ✅ |
| `CompileSourcePruner` (no call-site → drop) | ✅ |
| `IdeTypeSurface` (Union/Optional for IDE) | ✅ API; host parked |
| `ConverterE2ETest` + eval archive | ✅ |
| Annotation first-wins on conflicting call sites | ✅ replaced by specialize when multi-concrete |

### Work packages

#### B1. Productize the compile pipeline (CLI) — done skeleton

```bash
meridian compile path.py \
  [--calls usage.py | --calls-inline "..."] \
  [--specialize] \
  [--annotate-all] \
  [-o out_dir] \
  [--bench cases.json]
```

#### B2. Benchmark-gated annotation quality

Keep / extend existing gates (`ConverterE2ETest`, `MonomorphizationTest`,
`PolyOptionalCallSitesTest`, paper eval under `docs/meridian-eval/`).

#### B3. Specialization — done (+ call rewrite)

Multi-concrete call-site tuples → clones + dispatcher.
Library + usage call sites feed the plan (callees of hot entries included).
Library-internal concrete calls rewrite to `_name_<sig>` (skip dispatcher).

#### B4. Performance check protocol

CPython(naked) / mypyc(bare) / mypyc(Meridian), isolated dependency lanes.

#### B5. Tree-shake unused defs — done

`CompileSourcePruner` after annotate/specialize when usage is present.

---

## Part C — Outline optional / parametric call-site bindings (general)

`str`/`int` is **only an example**. The same rule applies to **every** distinct
concrete type tuple Outline/GCP binds at call sites.

### Policy (type-agnostic)

| Situation | Action |
|-----------|--------|
| All call sites share one concrete type tuple | Annotate original in place |
| Multiple concrete type tuples (any types) | Monomorphize: dispatcher + `_f_<typesig>` clones |
| Arg outline not concrete / not isinstance-erasable | Ignore that call site for specialization |
| Usage present, function never reached | Drop from compile input |
| No usage | Leave module; SAFE_PARTIAL annotate; no tree-shake |
| Never | First-wins merge of conflicting concrete bindings for compile |

---

## Sequencing

```text
Done / in tree
  B1 CLI compile skeleton ✅
  B3 specialize + isinstance dispatcher ✅
  B3b library call-site plan + rewrite to clones ✅
  B5 tree-shake unreachable ✅
  I0 IdeTypeSurface API ✅
  C  poly fixtures ✅
  Dual-surface policy documented ✅

Active
  B2 keep CompilePipelineGateTest / SpecializeCallRewriteTest green
  Expand real-usage fixtures; refresh docs/meridian-eval when benches change

Later (parked)
  Part A IDE/LSP host (I1–I5)
```

## Success metrics

1. ~~`meridian compile` runs a→c~~ ✅
2. ~~Multi-concrete call-site fixture compiles and runs~~ ✅
3. Unused library functions with usage present are absent from mypyc input ✅
4. IDE hover map can show Union/Optional without going through compile filter ✅
5. LSP host remains parked until Part A is explicitly resumed
