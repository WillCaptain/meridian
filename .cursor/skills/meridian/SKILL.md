---
name: meridian
description: Checks Python types with Meridian, produces evidence reports, compiles safe candidate modules with mypyc, validates correctness and performance, and prepares production artifact handoff. Use when asked to type-check, annotate, optimize, compile, test, package, or deploy Python with Meridian.
---

# Meridian

Use Meridian as an evidence-gated optimization pipeline:

```text
Python source
  → type/site report
  → choose compile scope from evidence
  → annotate + mypyc
  → correctness tests
  → performance comparison
  → production artifact handoff
```

Do not require the user to choose “partial”, “whole package”, or “runtime
fallback” before analysis. Those are separate decisions:

- **Compile scope**: which modules mypyc compiles.
- **Packaging**: loose native extensions or a platform wheel.
- **Runtime policy**: native-only or Python fallback.

## Locate Meridian

Run:

```bash
python3 <skill-dir>/scripts/meridian_workflow.py doctor
```

Resolution order is `--meridian-bin`, `MERIDIAN_BIN`, `meridian` on `PATH`,
then a nearby Meridian checkout containing `bin/meridian`.

## Workflow

### 1. Check and report before compiling

For one module:

```bash
python3 <skill-dir>/scripts/meridian_workflow.py check \
  --source path/to/module.py \
  --report-dir build/meridian-report
```

This writes:

- `type-sites.json`
- `annotated.py`
- `report.json`
- `report.md`

Report unsupported or unresolved typing honestly. A successful command does not
mean every function is fully typed.

### 2. Choose compile scope

Use the narrowest scope that covers the measured workload:

1. Start from an application entry/facade and its observed calls.
2. For multi-module code, default to `keep_deps` plus import closure.
3. Compile all modules only when the requested package is small and the full
   compile passes correctness and performance gates.
4. Do not call unimported coverage modules “hot”.

Read [references/delivery.md](references/delivery.md) before production handoff.

### 3. Compile

Single module:

```bash
python3 <skill-dir>/scripts/meridian_workflow.py compile \
  --source path/to/module.py \
  --calls path/to/calls.py \
  --report-dir build/meridian
```

Package:

```bash
python3 <skill-dir>/scripts/meridian_workflow.py compile \
  --package-dir path/to/package \
  --primary app_facade \
  --calls path/to/calls.py \
  --annotation-mode keep_deps \
  --compile-imports \
  --report-dir build/meridian
```

For an explicit package compile set, replace `--compile-imports` with:

```bash
--compile-modules app_facade,hot_numeric,hot_parser
```

### 4. Test

Pass the project’s real correctness command:

```bash
python3 <skill-dir>/scripts/meridian_workflow.py compile ... \
  --test-command 'pytest -q tests/test_hot_paths.py'
```

The workflow records the test result but never invents a correctness claim.
When benchmark cases exist, also run Meridian’s native-vs-compiled benchmark.
Do not accept speedup unless outputs match first.

### 5. Report and production handoff

Always summarize:

- input and source hash;
- inferred-site count and report paths;
- annotation mode and compiled modules;
- generated `.so` / `.pyd` files;
- Python ABI and platform;
- correctness command and status;
- measured speedup, or “not measured”;
- fallback policy, or “not decided”;
- blockers and claim boundary.

Use [references/report-template.md](references/report-template.md).

## Deployment boundary

This skill may build and validate artifacts. It must not deploy to an unknown
environment. Before deployment, identify the target runtime, Python ABI,
architecture, package format, test command, and rollback/fallback policy.

The default production recommendation is:

1. compile only the evidence-backed import closure;
2. package native modules with the original Python source;
3. publish a wheel per supported ABI/platform;
4. preserve Python fallback until native parity is proven in that environment.

## Claim guardrails

- Meridian optimizes supported, inferred hot paths; it does not compile all
  dynamic Python safely.
- A `.so` is platform- and CPython-ABI-specific.
- A wheel can contain multiple compiled modules; “whole package” does not imply
  one monolithic `.so`.
- Type coverage, compile success, correctness, and speedup are distinct gates.
- Never report deployment readiness from compile success alone.
