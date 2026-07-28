# L2 corpus — more-itertools recipes (selected)

Proof that Meridian’s annotate → specialize → mypyc path works on
**third-party-shaped** pure-Python algorithms, not only hand-crafted playground demos.

## Provenance

Functions are **adapted** from the well-known itertools / [more-itertools](https://github.com/more-itertools/more-itertools)
recipe style (MIT). Annotations from upstream (if any) are stripped. Hot paths
use concrete `list`/`int`/`float` so mypyc can win; generators/`Iterator` APIs
are rewritten to list loops where needed.

This is **not** a full-package install/import-graph eval. Meridian still takes
library + usage snippets (same as `mini_project/`).

## Layout

| File | Role |
|------|------|
| `recipes.py` | Naked library |
| `calls.py` | Usage / type evidence |
| `cases.json` | Bench cases `[[fn,[args],iters],…]` |
| `manifest.json` | Corpus metadata |

## Gates (see `ProductionCorpusProofTest`)

1. **Coverage** — param / return annotation rates after infer+usage  
2. **Compile** — mypyc success on Meridian output  
3. **Correctness** — native CPython == Meridian `.so`  
4. **Perf** — median / mean `speedup_vs_native` on hot cases  
