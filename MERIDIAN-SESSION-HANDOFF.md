# Meridian session handoff

Copy into a fresh agent session when resuming Meridian work.

---

## Prompt

You are continuing **Meridian** — the GCP Python carrier, to be shipped as a
real Python inferencer (annotations → mypyc), with TypeEvalPy harness scores
as an outcome.

### Read first

1. [`MERIDIAN-PRODUCT-ROADMAP.md`](MERIDIAN-PRODUCT-ROADMAP.md) — product phases
2. **Latest Outline toplas** (sibling `../outline`, tag `toplas-typeevalpy-513`):
   - `outline/src/test/java/org/twelve/outline/toplas/TYPEEVALPY-FACT-MANIFEST.csv`
   - `.../SOTA-PROTOCOL.md`, `REPRODUCE.md`, `common/TypeEvalPyFactSupport.java`
3. Progress report: [`meridian-python/docs/typeevalpy-micro-progress.md`](meridian-python/docs/typeevalpy-micro-progress.md)
4. [`meridian-python/spec/plan.md`](meridian-python/spec/plan.md) — converter backlog
5. Claim boundary: Outline-port 513/513 ≠ Meridian native `N/513`

### Current resume point

- Phase 0–1 done; Phase 2 **progress harness** uses Outline’s latest 513 manifest
- Re-score: `python3 scripts/run-typeevalpy-micro-progress.py`
- Adapter scaffold: `typeevalpy-adapter/` (copy into TypeEvalPy `target_tools/meridian`)
- **Next**: close MISS_LOCATION / MISS_TYPE gaps (Meridian adapters); optional Docker wire-up
- Do not edit Outline toplas for Meridian demos

### Sibling repos

```text
github/msll
github/gcp
github/meridian   ← you are here
github/outline    ← frozen toplas SOTA; do not disturb for Meridian demos
```

### Commands

```bash
mvn -B install -DskipTests -f ../msll/pom.xml
mvn -B install -DskipTests -f ../gcp/pom.xml
mvn -B test -f meridian-python/pom.xml
```

### Guardrails

- Public claim scope: harness ≠ Outline-port 513
- Baseline for speed: unannotated CPython vs Meridian-annotated mypyc
- All public docs English; chat with user in Chinese
