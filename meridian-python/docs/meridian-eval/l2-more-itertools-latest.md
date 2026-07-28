# L2 more-itertools recipes (selected)

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `l2-more-itertools`
- Captured: `20260728T013903Z`

## Summary

- param coverage: **100.0%** (24 / 24)
- return coverage: **81.8%**
- mypyc compile: **ok**
- correct rate: **100%**
- avg speedup vs native: **5.66×**
- gates pass: **true** (min param≥70%, avg speedup≥2.0×)

- partially unannotated (leave-gap ok): `nth`, `apply_twice`

| Function | correct | native ns | Meridian ns | Meridian× |
|----------|---------|-----------|-------------|----------|
| `quantify([0, 1, 0, 1, 1, 0, 1, 1, 0, 1], 10)` | true | 444.5 | 100.3 | 4.43 |
| `nth([1, 2, 3, 4, 5, 6, 7, 8], 3)` | true | 44.4 | 48.7 | 0.91 |
| `take_sum(5, [1, 2, 3, 4, 5, 6, 7, 8])` | true | 246.6 | 73.1 | 3.38 |
| `all_equal([3, 3, 3, 3, 3], 5)` | true | 226.1 | 83.1 | 2.72 |
| `dotproduct([1.0, 2.0, 3.0, 4.0, 5.0], [0.5, 0.5, 0.5, 0.5, 0.5], 5)` | true | 341.5 | 77.2 | 4.42 |
| `ncycles_sum([1, 2, 3, 4, 5, 6, 7, 8], 3, 8)` | true | 1010.7 | 194.9 | 5.19 |
| `first_true_index([0, 0, 0, 0, 0, 7, 0], 7)` | true | 248.0 | 67.3 | 3.69 |
| `pairwise_product_sum([1, 2, 3, 4, 5, 6, 7, 8], 8)` | true | 400.7 | 118.9 | 3.37 |
| `consume_count([1, 2, 3, 4, 5, 6, 7, 8], 8)` | true | 364.5 | 103.0 | 3.54 |
| `tabulate_sum(200, 1)` | true | 8496.3 | 340.7 | 24.94 |

