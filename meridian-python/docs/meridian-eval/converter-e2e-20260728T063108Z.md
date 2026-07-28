# Converter E2E — Meridian vs bare mypyc / native

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `converter-e2e`
- Captured: `20260728T063108Z`

## Summary

- converters: **24**
- avg bare×: **0.93**
- avg Meridian×: **11.24**

| Converter | bare× | Meridian× | Meridian/bare |
|-----------|-------|----------|---------------|
| `assert_isinstance` | 0.70 | 15.84 | 22.53 |
| `aug_assign` | 0.86 | 15.43 | 18.01 |
| `builtin_sorted` | 1.05 | 10.45 | 9.92 |
| `builtins` | 0.91 | 3.69 | 4.06 |
| `class_method` | 0.78 | 2.12 | 2.74 |
| `cross_module` | 0.93 | 17.49 | 18.81 |
| `default_params` | 0.88 | 13.99 | 15.90 |
| `dict_type` | 0.97 | 2.55 | 2.63 |
| `enumerate_zip` | 0.81 | 23.39 | 29.00 |
| `for_loop_var` | 0.86 | 17.96 | 20.88 |
| `fstring` | 0.78 | 16.42 | 21.04 |
| `ifexp` | 0.92 | 20.15 | 21.90 |
| `lambda` | 0.77 | 21.05 | 27.22 |
| `list_method` | 0.92 | 2.80 | 3.03 |
| `list_param` | 0.89 | 4.58 | 5.17 |
| `listcomp` | 1.00 | 3.54 | 3.56 |
| `match_case` | 0.98 | 23.74 | 24.31 |
| `method_call` | 1.01 | 3.99 | 3.96 |
| `module_lambda_to_def` | 1.02 | 2.27 | 2.23 |
| `named_expr` | 1.02 | 22.98 | 22.61 |
| `starred` | 1.00 | 7.29 | 7.29 |
| `subscript` | 1.07 | 1.67 | 1.56 |
| `tuple_unpack` | 1.04 | 11.97 | 11.51 |
| `yield_gen` | 1.15 | 4.43 | 3.85 |

