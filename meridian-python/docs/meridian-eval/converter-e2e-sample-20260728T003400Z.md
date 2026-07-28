# Converter E2E sample — Meridian CompilePipeline

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `converter-e2e-sample`
- Captured: `20260728T003400Z`

## Sample converters

| Converter | correct | speedup_vs_native |
|-----------|---------|-------------------|
| `aug_assign` | true | 27.09 |
| `listcomp` | true | 1.66 |
| `for_loop_var` | true | 16.32 |
| `ifexp` | true | 20.23 |

