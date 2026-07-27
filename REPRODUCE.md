# Reproduce Meridian (dev)

## Sibling layout

```text
github/msll
github/gcp
github/meridian   ← this repo
```

Install engine jars once:

```bash
mvn -B install -DskipTests -f ../msll/pom.xml
mvn -B install -DskipTests -f ../gcp/pom.xml
```

## Tests

Full suite includes mypyc E2E benches (slow; needs Python + mypyc):

```bash
mvn -B test -f meridian-python/pom.xml
```

Fast smoke (skip converter E2E):

```bash
mvn -B test -f meridian-python/pom.xml -Dtest='!*E2E*,!*Demo*,!*Benchmark*'
```

## CLI (Phase 0)

```bash
chmod +x bin/meridian
./bin/meridian infer path/to/file.py
./bin/meridian stub  path/to/file.py -o out.pyi
./bin/meridian sites path/to/file.py -o main_result.json
```

`sites` emits Scalpel/TypeEvalPy-style facts (`file`, `line_number`, `col_offset`,
`function` / `parameter` / `variable`, `type: [...]`). Columns are **1-based** to
match TypeEvalPy ground truth (Python AST is 0-based).

Requires `python3` on `PATH` (AST dump bridge).

## Product plan

See [`MERIDIAN-PRODUCT-ROADMAP.md`](MERIDIAN-PRODUCT-ROADMAP.md).
