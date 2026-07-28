# L3 more-itertools multi-module package

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `l3-more-itertools`
- Captured: `20260728T023548Z`

## Summary

- modules: `mi_numeric`, `mi_recipes`, `mi_facade` (primary `mi_recipes`)
- param coverage: **95.7%** (22 / 23)
- return coverage: **80.0%**
- mypyc multi-file compile: **ok**
- correct rate: **100%**
- avg speedup vs native: **3.63×**
- gates pass: **true**

| Function | correct | native ns | Meridian ns | Meridian× |
|----------|---------|-----------|-------------|----------|
| `quantify([0, 1, 0, 1, 1, 0, 1, 1, 0, 1], 10)` | true | 773.9 | 119.6 | 6.47 |
| `take_sum(5, [1, 2, 3, 4, 5, 6, 7, 8])` | true | 253.2 | 72.4 | 3.50 |
| `all_equal([3, 3, 3, 3, 3], 5)` | true | 238.5 | 59.4 | 4.02 |
| `ncycles_sum([1, 2, 3, 4, 5, 6, 7, 8], 3, 8)` | true | 944.9 | 186.7 | 5.06 |
| `pairwise_product_sum([1, 2, 3, 4, 5, 6, 7, 8], 8)` | true | 384.4 | 111.6 | 3.44 |
| `first_true_index([0, 0, 0, 0, 0, 0, 7], 7)` | true | 263.0 | 66.9 | 3.93 |
| `dotproduct([1.0, 2.0, 3.0, 4.0, 5.0], [0.5, 0.5, 0.5, 0.5, 0.5], 5)` | true | 342.9 | 260.6 | 1.32 |
| `recipe_tabulate(200, 1)` | true | 7923.6 | 6208.2 | 1.28 |

