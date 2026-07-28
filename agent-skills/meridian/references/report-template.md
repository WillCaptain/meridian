# Meridian report template

Use this structure when presenting a run:

```markdown
# Meridian optimization report

## Outcome
- Type check: passed | failed
- Compile: passed | failed | not run
- Correctness: passed | failed | not run
- Performance: N× | regression | not measured
- Production readiness: ready for target | blocked | build artifact only

## Scope
- Input:
- Primary:
- Compile strategy: single | import closure | explicit | full package
- Compiled modules:
- Annotation mode:

## Evidence
- Type sites:
- Source hashes:
- Native artifacts:
- Python ABI/platform:
- Test command:
- Benchmark:

## Risks and boundary
- Unsupported/untyped code:
- Dynamic features:
- Packaging:
- Fallback/rollback:
- Claim boundary:

## Next action
- One concrete action.
```

Rules:

- Lead with gate outcomes, not generated filenames.
- Say “not measured” instead of implying speedup.
- Say “build artifact only” until target-environment gates pass.
- Link report files and logs when the host supports file links.
