# L5 upstream more-itertools package surface

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `l5-more-itertools-pkg`
- Captured: `20260728T050133Z`

## Summary

- modules: `mi_recipes`, `mi_more`, `mi_facade` (primary `mi_facade`)
- param coverage: **28.5%** (35 / 123)
- return coverage: **25.7%**
- mypyc multi-file compile: **ok**
- correct rate: **100%**
- avg speedup vs native: **1.17×**
- gates pass: **true**

| Function | correct | native ns | Meridian ns | Meridian× |
|----------|---------|-----------|-------------|----------|
| `take_list(3, [1, 2, 3, 4, 5, 6, 7, 8])` | true | 410.0 | 341.6 | 1.20 |
| `quantify_bool([True, False, True, True])` | true | 349.1 | 240.0 | 1.45 |
| `nth_item([1, 2, 3, 4, 5, 6, 7, 8], 3)` | true | 185.0 | 198.0 | 0.93 |
| `all_equal_seq([3, 3, 3, 3])` | true | 254.5 | 190.7 | 1.33 |
| `dotproduct_seq([10.0, 15.0, 12.0], [0.65, 0.8, 1.25])` | true | 248.9 | 247.3 | 1.01 |
| `first_true_val([0, 0, 7, 0])` | true | 162.0 | 124.0 | 1.31 |
| `ilen_seq([1, 2, 3, 4, 5, 6, 7, 8])` | true | 439.0 | 426.2 | 1.03 |
| `chunked_lens([1, 2, 3, 4, 5, 6, 7, 8], 3)` | true | 1192.6 | 946.9 | 1.26 |
| `flatten_list([[0, 1], [2, 3], [4]])` | true | 264.3 | 261.1 | 1.01 |

