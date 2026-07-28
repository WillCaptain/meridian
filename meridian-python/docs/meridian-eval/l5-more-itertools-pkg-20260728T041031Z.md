# L5 upstream more-itertools package surface

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `l5-more-itertools-pkg`
- Captured: `20260728T041031Z`

## Summary

- modules: `mi_recipes`, `mi_more`, `mi_facade` (primary `mi_facade`)
- param coverage: **28.5%** (35 / 123)
- return coverage: **25.7%**
- mypyc multi-file compile: **ok**
- correct rate: **100%**
- avg speedup vs native: **1.23×**
- gates pass: **true**

| Function | correct | native ns | Meridian ns | Meridian× |
|----------|---------|-----------|-------------|----------|
| `take_list(3, [1, 2, 3, 4, 5, 6, 7, 8])` | true | 700.0 | 372.8 | 1.88 |
| `quantify_bool([True, False, True, True])` | true | 286.2 | 240.9 | 1.19 |
| `nth_item([1, 2, 3, 4, 5, 6, 7, 8], 3)` | true | 223.9 | 185.3 | 1.21 |
| `all_equal_seq([3, 3, 3, 3])` | true | 256.9 | 190.7 | 1.35 |
| `dotproduct_seq([10.0, 15.0, 12.0], [0.65, 0.8, 1.25])` | true | 233.0 | 237.9 | 0.98 |
| `first_true_val([0, 0, 7, 0])` | true | 125.6 | 129.9 | 0.97 |
| `ilen_seq([1, 2, 3, 4, 5, 6, 7, 8])` | true | 394.3 | 397.0 | 0.99 |
| `chunked_lens([1, 2, 3, 4, 5, 6, 7, 8], 3)` | true | 1086.7 | 890.6 | 1.22 |
| `flatten_list([[0, 1], [2, 3], [4]])` | true | 255.4 | 198.2 | 1.29 |

