# math_utils four-way — Meridian vs native / manual oracle

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `table1-math-utils`
- Captured: `20260728T010351Z`

## Summary

- infer median: **142 ms**
- annotate median: **16 ms**
- avg bare×: **1.25**
- avg Meridian×: **13.13**
- avg vs manual: **97.3%**

| Function | bare× | Meridian× | manual× | vs manual% |
|----------|-------|----------|---------|------------|
| `factorial(10)` | 1.88 | 8.36 | 8.48 | 98.6 |
| `factorial(20)` | 1.59 | 2.38 | 2.39 | 99.6 |
| `fibonacci(30)` | 1.0 | 6.0 | 6.0 | 100.0 |
| `sum_squares(100)` | 0.9 | 13.48 | 14.36 | 93.9 |
| `sum_squares(1000)` | 0.77 | 18.93 | 19.33 | 97.9 |
| `is_prime(997)` | 1.39 | 16.58 | 16.06 | 103.2 |
| `is_prime(9999991)` | 1.23 | 26.16 | 29.77 | 87.9 |

