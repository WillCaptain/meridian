# TheAlgorithms maths — Meridian vs native CPython

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `thealgorithms-maths`
- Captured: `20260728T003723Z`

## Summary

- avg bare×: **1.51**
- avg Meridian×: **17.20**

| Function | correct | bare× | Meridian× |
|----------|---------|-------|----------|
| `gcd_euclidean(1071, 462)` | true | 1.62 | 3.48 |
| `gcd_euclidean(123456789, 987654)` | true | 1.83 | 4.16 |
| `prime_factors_count(9699690)` | true | 1.41 | 15.99 |
| `prime_factors_count(1000000007)` | true | 1.17 | 25.86 |
| `euler_totient(100)` | true | 1.77 | 23.87 |
| `euler_totient(200)` | true | 1.78 | 23.20 |
| `sieve_count(10000)` | true | 0.99 | 12.16 |
| `sieve_count(50000)` | true | 0.98 | 12.54 |
| `collatz_len(871)` | true | 1.66 | 37.18 |
| `collatz_len(77031)` | true | 1.61 | 38.94 |
| `pow_mod(2, 1000, 1000007)` | true | 1.49 | 11.35 |
| `pow_mod(7, 10000, 999999937)` | true | 1.50 | 13.30 |
| `sum_of_digits(123456789)` | true | 1.68 | 9.29 |
| `sum_of_digits(999999999999)` | true | 1.59 | 9.42 |

