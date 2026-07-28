# math_utils four-way — Meridian vs native / manual oracle

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `table1-math-utils`
- Captured: `20260728T003801Z`

## Summary

- infer median: **97 ms**
- annotate median: **11 ms**
- avg bare×: **1.23**
- avg Meridian×: **13.57**
- avg vs manual: **99.2%**

| Function | bare× | Meridian× | manual× | vs manual% |
|----------|-------|----------|---------|------------|
| `factorial(10)` | 1.91 | 8.39 | 8.52 | 98.5 |
| `factorial(20)` | 1.46 | 2.39 | 2.45 | 97.6 |
| `fibonacci(30)` | 1.02 | 6.11 | 6.09 | 100.3 |
| `sum_squares(100)` | 0.9 | 13.95 | 14.2 | 98.2 |
| `sum_squares(1000)` | 0.75 | 18.29 | 18.73 | 97.7 |
| `is_prime(997)` | 1.36 | 15.73 | 15.64 | 100.6 |
| `is_prime(9999991)` | 1.19 | 30.14 | 29.67 | 101.6 |

