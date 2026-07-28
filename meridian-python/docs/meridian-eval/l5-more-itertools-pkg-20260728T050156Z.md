# L5 upstream more-itertools package surface

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `l5-more-itertools-pkg`
- Captured: `20260728T050156Z`

## Summary

- modules: `mi_recipes`, `mi_more`, `mi_facade` (primary `mi_facade`)
- param coverage: **28.5%** (35 / 123)
- return coverage: **25.7%**
- mypyc multi-file compile: **ok**
- correct rate: **100%**
- avg speedup vs native: **1.08×**
- gates pass: **true**

| Function | correct | native ns | Meridian ns | Meridian× |
|----------|---------|-----------|-------------|----------|
| `take_list(3, [1, 2, 3, 4, 5, 6, 7, 8])` | true | 271.2 | 200.1 | 1.36 |
| `quantify_bool([True, False, True, True])` | true | 183.1 | 190.7 | 0.96 |
| `nth_item([1, 2, 3, 4, 5, 6, 7, 8], 3)` | true | 177.2 | 172.3 | 1.03 |
| `all_equal_seq([3, 3, 3, 3])` | true | 236.3 | 189.5 | 1.25 |
| `dotproduct_seq([10.0, 15.0, 12.0], [0.65, 0.8, 1.25])` | true | 243.0 | 260.0 | 0.93 |
| `first_true_val([0, 0, 7, 0])` | true | 132.9 | 123.7 | 1.07 |
| `ilen_seq([1, 2, 3, 4, 5, 6, 7, 8])` | true | 440.9 | 415.9 | 1.06 |
| `chunked_lens([1, 2, 3, 4, 5, 6, 7, 8], 3)` | true | 1159.7 | 1181.0 | 0.98 |
| `flatten_list([[0, 1], [2, 3], [4]])` | true | 263.9 | 250.0 | 1.06 |

