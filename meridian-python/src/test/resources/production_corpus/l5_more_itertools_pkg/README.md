# L5 corpus — upstream more-itertools package surface

Escalation from L4: **nearly full** `recipes.py` plus a **selected** `more.py`
slice, as a multi-module import graph (`mi_facade` → `mi_recipes` / `mi_more`).

## Claim boundary

- Upstream more-itertools v10.7.0 (MIT), not Meridian-rewritten list loops.
- `mi_recipes`: nearly full recipes (totient dropped; `_zip_strict` dual-bind
  flattened so mypyc sees one binding type).
- `mi_more`: selected bodies without missing internal deps (`chunked`, `first`,
  `last`, `ilen`, `one`, …) — not the full ~5000-line `more.py`.
- Coverage is measured on Meridian `SAFE_PARTIAL` annotations across the package.
- Before mypyc: non-primary modules are annotation-stripped (large upstream
  surfaces over-widen); primary facade keeps Meridian parameter annotations.

## Gates

Same four gates as L2–L4 (param coverage, mypyc multi-file compile, correct
rate, avg speedup vs native). Expect modest speedups vs rewritten list corpora.
