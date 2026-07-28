# L3 corpus — more-itertools multi-module package

Escalation from L2: **real import graph** across modules shaped like
[more-itertools](https://github.com/more-itertools/more-itertools) (`recipes` +
leaf helpers + facade), not a single concatenated file.

## Claim boundary

- Package layout + absolute imports (`from mi_numeric import …`).
- Selected recipes **adapted** to concrete `list`/`int`/`float` hot paths so mypyc
  can win (upstream uses lazy iterators / `itertools` heavily).
- Not a pip-install + full 5000-line `more.py` compile.

## Layout

```
package/
  mi_numeric.py    # leaf helpers
  mi_recipes.py    # imports mi_numeric; recipe-style hot funcs
  mi_facade.py     # re-exports for usage / bench entry
calls.py / calls_bench.py / cases.json / manifest.json
```

## Gates

Same four gates as L2, plus **cross-module**: recipes must import numeric and
mypyc compiles both together.
