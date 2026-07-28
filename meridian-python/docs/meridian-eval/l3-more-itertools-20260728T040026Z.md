# L3 more-itertools multi-module package

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `l3-more-itertools`
- Captured: `20260728T040026Z`

## Summary

- modules: `mi_numeric`, `mi_recipes`, `mi_facade` (primary `mi_recipes`)
- param coverage: **95.7%** (22 / 23)
- return coverage: **80.0%**
- mypyc multi-file compile: **ok**
- correct rate: **100%**
- avg speedup vs native: **3.26×**
- gates pass: **true**

| Function | correct | native ns | Meridian ns | Meridian× |
|----------|---------|-----------|-------------|----------|
| `quantify([0, 1, 0, 1, 1, 0, 1, 1, 0, 1], 10)` | true | 439.0 | 96.3 | 4.56 |
| `take_sum(5, [1, 2, 3, 4, 5, 6, 7, 8])` | true | 225.0 | 69.3 | 3.25 |
| `all_equal([3, 3, 3, 3, 3], 5)` | true | 220.3 | 62.1 | 3.55 |
| `ncycles_sum([1, 2, 3, 4, 5, 6, 7, 8], 3, 8)` | true | 979.3 | 214.8 | 4.56 |
| `pairwise_product_sum([1, 2, 3, 4, 5, 6, 7, 8], 8)` | true | 420.8 | 111.6 | 3.77 |
| `first_true_index([0, 0, 0, 0, 0, 0, 7], 7)` | true | 275.9 | 73.6 | 3.75 |
| `dotproduct([1.0, 2.0, 3.0, 4.0, 5.0], [0.5, 0.5, 0.5, 0.5, 0.5], 5)` | true | 360.1 | 310.3 | 1.16 |
| `recipe_tabulate(200, 1)` | true | 8100.4 | 5574.7 | 1.45 |

