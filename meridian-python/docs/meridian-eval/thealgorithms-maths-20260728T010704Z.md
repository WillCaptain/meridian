# TheAlgorithms maths — Meridian vs native CPython

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `thealgorithms-maths`
- Captured: `20260728T010704Z`

## Summary

- avg bare×: **1.50**
- avg Meridian×: **16.96**

| Function | correct | bare× | Meridian× |
|----------|---------|-------|----------|
| `gcd_euclidean(1071, 462)` | true | 1.84 | 4.17 |
| `gcd_euclidean(123456789, 987654)` | true | 1.66 | 3.08 |
| `prime_factors_count(9699690)` | true | 1.42 | 15.58 |
| `prime_factors_count(1000000007)` | true | 1.12 | 24.74 |
| `euler_totient(100)` | true | 1.79 | 23.40 |
| `euler_totient(200)` | true | 1.79 | 22.80 |
| `sieve_count(10000)` | true | 0.99 | 12.27 |
| `sieve_count(50000)` | true | 0.99 | 12.69 |
| `collatz_len(871)` | true | 1.60 | 37.57 |
| `collatz_len(77031)` | true | 1.57 | 37.02 |
| `pow_mod(2, 1000, 1000007)` | true | 1.50 | 11.78 |
| `pow_mod(7, 10000, 999999937)` | true | 1.45 | 13.72 |
| `sum_of_digits(123456789)` | true | 1.66 | 9.29 |
| `sum_of_digits(999999999999)` | true | 1.60 | 9.30 |

