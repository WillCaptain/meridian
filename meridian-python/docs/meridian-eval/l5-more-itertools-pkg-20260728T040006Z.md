# L5 upstream more-itertools package surface

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `l5-more-itertools-pkg`
- Captured: `20260728T040006Z`

## Summary

- modules: `mi_recipes`, `mi_more`, `mi_facade` (primary `mi_facade`)
- param coverage: **28.5%** (35 / 123)
- return coverage: **25.7%**
- mypyc multi-file compile: **ok**
- correct rate: **100%**
- avg speedup vs native: **1.22×**
- gates pass: **true**

| Function | correct | native ns | Meridian ns | Meridian× |
|----------|---------|-----------|-------------|----------|
| `take_list(3, [1, 2, 3, 4, 5, 6, 7, 8])` | true | 583.9 | 347.4 | 1.68 |
| `quantify_bool([True, False, True, True])` | true | 262.4 | 232.8 | 1.13 |
| `nth_item([1, 2, 3, 4, 5, 6, 7, 8], 3)` | true | 214.3 | 186.1 | 1.15 |
| `all_equal_seq([3, 3, 3, 3])` | true | 244.4 | 196.2 | 1.25 |
| `dotproduct_seq([10.0, 15.0, 12.0], [0.65, 0.8, 1.25])` | true | 231.1 | 231.5 | 1.00 |
| `first_true_val([0, 0, 7, 0])` | true | 131.4 | 119.1 | 1.10 |
| `ilen_seq([1, 2, 3, 4, 5, 6, 7, 8])` | true | 515.2 | 423.4 | 1.22 |
| `chunked_lens([1, 2, 3, 4, 5, 6, 7, 8], 3)` | true | 1093.7 | 872.5 | 1.25 |
| `flatten_list([[0, 1], [2, 3], [4]])` | true | 255.5 | 207.2 | 1.23 |

