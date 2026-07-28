# mini_project — real-shaped `--calls` sample

Tiny analytics-style library used as a product gate:

- `stats_kit.py` — naked library (hot path, helper, poly `tag`, unused dead code)
- `calls.py` — usage / entry evidence (what `meridian compile --calls` consumes)

Not a full package with imports: Meridian joint inference takes library + usage
snippets the same way as other benches (`hot(10)` style names).
