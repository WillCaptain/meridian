# L3 more-itertools multi-module package

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `l3-more-itertools`
- Captured: `20260728T041031Z`

## Summary

- modules: `mi_numeric`, `mi_recipes`, `mi_facade` (primary `mi_recipes`)
- param coverage: **95.7%** (22 / 23)
- return coverage: **80.0%**
- mypyc multi-file compile: **ok**
- correct rate: **100%**
- avg speedup vs native: **3.29×**
- gates pass: **true**

| Function | correct | native ns | Meridian ns | Meridian× |
|----------|---------|-----------|-------------|----------|
| `quantify([0, 1, 0, 1, 1, 0, 1, 1, 0, 1], 10)` | true | 443.4 | 98.6 | 4.50 |
| `take_sum(5, [1, 2, 3, 4, 5, 6, 7, 8])` | true | 223.2 | 67.4 | 3.31 |
| `all_equal([3, 3, 3, 3, 3], 5)` | true | 216.0 | 64.4 | 3.36 |
| `ncycles_sum([1, 2, 3, 4, 5, 6, 7, 8], 3, 8)` | true | 932.2 | 177.7 | 5.25 |
| `pairwise_product_sum([1, 2, 3, 4, 5, 6, 7, 8], 8)` | true | 397.4 | 113.1 | 3.51 |
| `first_true_index([0, 0, 0, 0, 0, 0, 7], 7)` | true | 280.1 | 74.0 | 3.78 |
| `dotproduct([1.0, 2.0, 3.0, 4.0, 5.0], [0.5, 0.5, 0.5, 0.5, 0.5], 5)` | true | 351.8 | 283.2 | 1.24 |
| `recipe_tabulate(200, 1)` | true | 7977.5 | 5762.4 | 1.38 |

