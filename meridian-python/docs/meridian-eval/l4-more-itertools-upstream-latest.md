# L4 upstream more-itertools recipe bodies

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `l4-more-itertools-upstream`
- Captured: `20260728T034702Z`

## Summary

- param coverage: **85.7%**
- mypyc compile: **ok**
- correct rate: **100%**
- avg speedup vs native: **2.24×**
- gates pass: **true**

| Function | correct | native ns | Meridian ns | Meridian× |
|----------|---------|-----------|-------------|----------|
| `take_sum(5, [1, 2, 3, 4, 5, 6, 7, 8])` | true | 566.3 | 326.8 | 1.73 |
| `nth_item([1, 2, 3, 4, 5, 6, 7, 8], 3)` | true | 222.0 | 187.9 | 1.18 |
| `quantify_bool([True, False, True, True])` | true | 209.7 | 49.0 | 4.28 |
| `dotproduct_seq([10.0, 15.0, 12.0], [0.65, 0.8, 1.25])` | true | 386.2 | 217.5 | 1.78 |

