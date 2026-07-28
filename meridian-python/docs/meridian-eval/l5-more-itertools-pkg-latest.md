# L5 upstream more-itertools package surface

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `l5-more-itertools-pkg`
- Captured: `20260728T040026Z`

## Summary

- modules: `mi_recipes`, `mi_more`, `mi_facade` (primary `mi_facade`)
- param coverage: **28.5%** (35 / 123)
- return coverage: **25.7%**
- mypyc multi-file compile: **ok**
- correct rate: **100%**
- avg speedup vs native: **1.14×**
- gates pass: **true**

| Function | correct | native ns | Meridian ns | Meridian× |
|----------|---------|-----------|-------------|----------|
| `take_list(3, [1, 2, 3, 4, 5, 6, 7, 8])` | true | 256.2 | 197.4 | 1.30 |
| `quantify_bool([True, False, True, True])` | true | 175.4 | 174.9 | 1.00 |
| `nth_item([1, 2, 3, 4, 5, 6, 7, 8], 3)` | true | 175.8 | 157.4 | 1.12 |
| `all_equal_seq([3, 3, 3, 3])` | true | 235.8 | 188.9 | 1.25 |
| `dotproduct_seq([10.0, 15.0, 12.0], [0.65, 0.8, 1.25])` | true | 243.3 | 253.8 | 0.96 |
| `first_true_val([0, 0, 7, 0])` | true | 133.3 | 123.4 | 1.08 |
| `ilen_seq([1, 2, 3, 4, 5, 6, 7, 8])` | true | 406.0 | 400.5 | 1.01 |
| `chunked_lens([1, 2, 3, 4, 5, 6, 7, 8], 3)` | true | 1121.0 | 882.4 | 1.27 |
| `flatten_list([[0, 1], [2, 3], [4]])` | true | 256.9 | 204.9 | 1.25 |

