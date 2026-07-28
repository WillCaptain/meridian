# Meridian

**Meridian** is a deterministic zero-annotation **Python type inferencer** built on
[GCP](https://github.com/WillCaptain/gcp) (Generic Constraint Projection).

```text
unannotated Python  →  Meridian (GCP)  →  PEP 484 annotations  →  mypyc  →  faster native code
```

TypeEvalPy / Autogen leaderboard numbers are a **downstream outcome** of this
pipeline on unmodified Python — not the definition of the product.

## Repo layout

| Path | Role |
|------|------|
| [`meridian-python/`](meridian-python/) | Python → ASF → GCP → annotate → mypyc |
| [`MERIDIAN-PRODUCT-ROADMAP.md`](MERIDIAN-PRODUCT-ROADMAP.md) | Product phases (start here) |
| [`MERIDIAN-SESSION-HANDOFF.md`](MERIDIAN-SESSION-HANDOFF.md) | Agent / session resume prompt |
| [`meridian-python/spec/plan.md`](meridian-python/spec/plan.md) | Converter coverage backlog |
| [`meridian-python/docs/`](meridian-python/docs/) | Experiments & feature coverage |
| [`agent-skills/`](agent-skills/) | Host-neutral Meridian skill + Cursor/Codex/Claude Code adapters |

## Quick start (dev)

Sibling checkouts: `msll`, `gcp`, `meridian`.

```bash
mvn -B install -DskipTests -f ../msll/pom.xml
mvn -B install -DskipTests -f ../gcp/pom.xml
mvn -B test -f meridian-python/pom.xml
```

### CLI

```bash
chmod +x bin/meridian
./bin/meridian infer path/to/file.py          # annotated Python → stdout
./bin/meridian stub  path/to/file.py -o x.pyi
./bin/meridian sites path/to/file.py          # TypeEvalPy FR/FP/LV JSON
```

Or: `mvn -q -f meridian-python/pom.xml exec:java -Dexec.args='infer file.py'`

See [`REPRODUCE.md`](REPRODUCE.md). Product phases: [`MERIDIAN-PRODUCT-ROADMAP.md`](MERIDIAN-PRODUCT-ROADMAP.md).

### Agent skill

Use the portable Meridian skill to run type report → selective compile →
correctness/performance gates → production artifact handoff:

```bash
python3 agent-skills/meridian/scripts/meridian_workflow.py check \
  --source path/to/file.py --report-dir build/meridian-report
```

Cursor, Codex, and Claude Code installation adapters are documented under
[`agent-skills/meridian/adapters/`](agent-skills/meridian/adapters/).

## TypeEvalPy micro progress (native Meridian)

Fact IDs come from the **latest Outline** toplas manifest (keep Outline updated).
Primary gate is **strict** exact-locator pairing; **compat** allows soft locators
but never uses expected types. Legacy oracle-assisted 513/513 is archived only.

```bash
python3 scripts/run-typeevalpy-micro-progress.py --mode both \
  --manifest ../outline/outline/src/test/java/org/twelve/outline/toplas/TYPEEVALPY-FACT-MANIFEST.csv
```

Report: [`meridian-python/docs/typeevalpy-micro-progress.md`](meridian-python/docs/typeevalpy-micro-progress.md) ·
[strict vs compat](meridian-python/docs/typeevalpy-micro-progress-strict-vs-compat.md).  
Docker drop-in scaffold: [`typeevalpy-adapter/`](typeevalpy-adapter/).

## Related work

- GCP theory paper (Outline-port TypeEvalPy 513/513): arXiv:2607.19693
- Outline frozen artefact: https://github.com/WillCaptain/outline/releases/tag/toplas-typeevalpy-513
- HF dataset card: https://huggingface.co/datasets/will-zhang/typeevalpy-outline-port
