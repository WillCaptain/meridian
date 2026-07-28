# Converter E2E sample — Meridian CompilePipeline

> Meridian product evaluation (annotate → mypyc vs native CPython).
> Not a GCP core / TOPLAS claim.

- Suite: `converter-e2e-sample`
- Captured: `20260728T010341Z`

## Sample converters

| Converter | correct | speedup_vs_native |
|-----------|---------|-------------------|
| `aug_assign` | true | 28.04 |
| `listcomp` | true | 1.65 |
| `for_loop_var` | true | 16.64 |
| `ifexp` | true | 20.65 |

