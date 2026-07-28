# L6 corpus — full more.py coverage + annotated hot path

Escalation from L5: measure annotation coverage on a **nearly full** `more.py`
function surface, then mypyc-compile a **hot subgraph with Meridian parameter
annotations kept** (not stripped), using larger inputs.

## Why this split?

L5 proved the package can compile, but stripped dep annotations so mypyc saw
mostly dynamic code (~1.1×). A controlled typed-vs-naked check shows Meridian
annotations still matter (~3–6× on the same kernels). L6 restores that path
while still scanning the large upstream surface for coverage.

## Claim boundary

- Coverage: nearly all `more.py` top-level functions (v10.7.0), classes/thread
  helpers omitted.
- Compile/bench: `mi_hot` + `mi_facade` with `mypyc_annotation_mode=keep`.
- Larger bench inputs than L2–L5 micro-lists.
- Not every class / `AbortThread` / callback helper in upstream `more.py`.

## Gates

Four gates plus `funcs_total >= 80`. Expect higher speedups than L5 when
annotations are retained.
