#!/usr/bin/env python3
"""
Score Meridian on the TypeEvalPy soaps eight-category / 513-fact inventory.

Fact ID inventory SSOT = latest Outline toplas manifest (not Outline-port scoring):
  outline/.../toplas/TYPEEVALPY-FACT-MANIFEST.csv

Sources = unmodified TypeEvalPy micro-benchmark Python (local artifacts or clone).

This is a Meridian progress metric (N/513). It is NOT the Outline-port 513/513 claim.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import subprocess
import sys
import time
from collections import defaultdict
from pathlib import Path

EIGHT = (
    "assignments",
    "classes",
    "dicts",
    "direct_calls",
    "functions",
    "lambdas",
    "lists",
    "returns",
)

DEFAULT_OUTLINE = Path(__file__).resolve().parents[2] / "outline"
DEFAULT_MANIFEST = (
    DEFAULT_OUTLINE
    / "outline/src/test/java/org/twelve/outline/toplas/TYPEEVALPY-FACT-MANIFEST.csv"
)
DEFAULT_MICRO = Path(
    "/tmp/typeevalpy-artifacts/artifacts/results-microbenchmark/"
    "codestral-v0.1-22b-q&a-prompt/micro-benchmark/python_features"
)
MERIDIAN_ROOT = Path(__file__).resolve().parents[1]
MERIDIAN_BIN = MERIDIAN_ROOT / "bin" / "meridian"


def parse_expected(s: str) -> set[str]:
    if not s:
        return set()
    parts = [p.strip() for p in s.replace("|", ";").split(";") if p.strip()]
    return {standardize(p) for p in parts}


def standardize(t: str) -> str:
    x = t.strip()
    # Align with TypeEvalPy / Outline closed-world Python vocabulary
    aliases = {
        "None": "Nonetype",
        "NoneType": "Nonetype",
        "none": "Nonetype",
    }
    return aliases.get(x, x)


def load_manifest(path: Path) -> list[dict]:
    with path.open(newline="", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    eight = [r for r in rows if r["category"] in EIGHT]
    if len(eight) != 513:
        print(
            f"warning: expected 513 eight-category facts, got {len(eight)} from {path}",
            file=sys.stderr,
        )
    return eight


def site_key_fields(site: dict) -> tuple:
    kind = "FR"
    symbol = site.get("function")
    if "parameter" in site:
        kind = "FP"
        symbol = site.get("parameter")
    elif "variable" in site:
        kind = "LV"
        symbol = site.get("variable")
    return (
        site.get("file"),
        int(site.get("line_number", -1)),
        int(site.get("col_offset", -1)),
        kind,
        symbol,
    )


def fact_key_fields(fact: dict) -> tuple:
    return (
        fact["file"],
        int(fact["line"]),
        int(fact["column"]),
        fact["oracle_kind"],
        fact["symbol"],
    )


def run_meridian_sites(py_file: Path) -> list[dict]:
    cmd = [str(MERIDIAN_BIN), "sites", str(py_file)]
    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        cwd=str(MERIDIAN_ROOT),
        timeout=120,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            f"meridian sites failed ({proc.returncode}) for {py_file}:\n"
            f"{proc.stderr[-2000:]}"
        )
    out = proc.stdout.strip()
    if not out:
        return []
    data = json.loads(out)
    if not isinstance(data, list):
        raise RuntimeError(f"unexpected sites JSON for {py_file}")
    return data


def index_sites(sites: list[dict]) -> dict[tuple, dict]:
    idx = {}
    for s in sites:
        idx[site_key_fields(s)] = s
    return idx


def types_of(site: dict | None) -> set[str]:
    if not site:
        return set()
    return {standardize(t) for t in site.get("type", [])}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--manifest",
        type=Path,
        default=Path(os.environ.get("OUTLINE_FACT_MANIFEST", DEFAULT_MANIFEST)),
        help="Outline TYPEEVALPY-FACT-MANIFEST.csv (latest toplas)",
    )
    ap.add_argument(
        "--micro-root",
        type=Path,
        default=Path(os.environ.get("TYPEEVALPY_MICRO_ROOT", DEFAULT_MICRO)),
        help="python_features/ root with category/template/main.py",
    )
    ap.add_argument(
        "--out-json",
        type=Path,
        default=MERIDIAN_ROOT / "meridian-python/docs/typeevalpy-micro-progress.json",
    )
    ap.add_argument(
        "--out-md",
        type=Path,
        default=MERIDIAN_ROOT / "meridian-python/docs/typeevalpy-micro-progress.md",
    )
    ap.add_argument(
        "--limit-templates",
        type=int,
        default=0,
        help="debug: only first N templates",
    )
    args = ap.parse_args()

    if not args.manifest.is_file():
        print(f"manifest not found: {args.manifest}", file=sys.stderr)
        print(
            "Set OUTLINE_FACT_MANIFEST or clone WillCaptain/outline next to meridian.",
            file=sys.stderr,
        )
        return 2
    if not args.micro_root.is_dir():
        print(f"micro-root not found: {args.micro_root}", file=sys.stderr)
        return 2
    if not MERIDIAN_BIN.is_file():
        print(f"missing {MERIDIAN_BIN}", file=sys.stderr)
        return 2

    facts = load_manifest(args.manifest)
    by_template: dict[tuple[str, str], list[dict]] = defaultdict(list)
    for f in facts:
        by_template[(f["category"], f["template"])].append(f)

    templates = sorted(by_template.keys())
    if args.limit_templates:
        templates = templates[: args.limit_templates]

    results = []
    exact = 0
    located = 0
    missing_src = 0
    errors = 0
    t0 = time.time()

    for i, (cat, tmpl) in enumerate(templates, 1):
        tmpl_dir = args.micro_root / cat / tmpl
        main_py = tmpl_dir / "main.py"
        print(f"[{i}/{len(templates)}] {cat}/{tmpl}", flush=True)
        if not main_py.is_file():
            missing_src += 1
            for fact in by_template[(cat, tmpl)]:
                results.append(
                    {
                        "fact_id": fact["fact_id"],
                        "status": "MISSING_SOURCE",
                        "category": cat,
                        "template": tmpl,
                    }
                )
            continue

        # Infer all .py files in the template dir (covers imported.py).
        site_index: dict[tuple, dict] = {}
        try:
            for py in sorted(tmpl_dir.glob("*.py")):
                sites = run_meridian_sites(py)
                # Normalize file basename in case bridge wrote absolute names
                for s in sites:
                    s = dict(s)
                    s["file"] = py.name
                    site_index[site_key_fields(s)] = s
        except Exception as e:
            errors += 1
            print(f"  ERROR: {e}", file=sys.stderr)
            for fact in by_template[(cat, tmpl)]:
                results.append(
                    {
                        "fact_id": fact["fact_id"],
                        "status": "ERROR",
                        "error": str(e)[:500],
                        "category": cat,
                        "template": tmpl,
                    }
                )
            continue

        for fact in by_template[(cat, tmpl)]:
            key = fact_key_fields(fact)
            site = site_index.get(key)
            exp = parse_expected(fact["expected_types"])
            pred = types_of(site)
            if site is None:
                status = "MISS_LOCATION"
            elif pred == exp:
                status = "EXACT"
                exact += 1
                located += 1
            else:
                status = "MISS_TYPE"
                located += 1
            results.append(
                {
                    "fact_id": fact["fact_id"],
                    "status": status,
                    "category": cat,
                    "template": tmpl,
                    "expected": sorted(exp),
                    "predicted": sorted(pred) if site else [],
                    "mapping_outline": fact.get("mapping"),
                }
            )

    total = len(facts) if not args.limit_templates else len(results)
    by_cat = defaultdict(lambda: {"exact": 0, "total": 0})
    by_status = defaultdict(int)
    for r in results:
        by_status[r["status"]] += 1
        by_cat[r["category"]]["total"] += 1
        if r["status"] == "EXACT":
            by_cat[r["category"]]["exact"] += 1

    summary = {
        "claim_boundary": (
            "Meridian native-Python micro progress against Outline fact_id inventory. "
            "Not Outline-port FACT_PAIRED 513/513; not Autogen README #1."
        ),
        "outline_manifest": str(args.manifest.resolve()),
        "outline_release_ref": "toplas-typeevalpy-513 / TYPEEVALPY-FACT-MANIFEST.csv",
        "micro_root": str(args.micro_root.resolve()),
        "total_facts_scored": len(results),
        "exact": exact,
        "exact_rate": round(exact / len(results), 4) if results else 0.0,
        "located": located,
        "missing_source_templates": missing_src,
        "template_errors": errors,
        "elapsed_sec": round(time.time() - t0, 1),
        "by_category": {
            c: {
                "exact": by_cat[c]["exact"],
                "total": by_cat[c]["total"],
                "rate": round(by_cat[c]["exact"] / by_cat[c]["total"], 4)
                if by_cat[c]["total"]
                else 0.0,
            }
            for c in EIGHT
        },
        "by_status": dict(by_status),
    }

    args.out_json.parent.mkdir(parents=True, exist_ok=True)
    payload = {"summary": summary, "facts": results}
    args.out_json.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    md = [
        "# Meridian × TypeEvalPy micro progress (513 inventory)",
        "",
        "> **Not** Outline-port 513/513. Fact IDs from latest Outline toplas manifest;",
        "> inference via Meridian native Python path.",
        "",
        f"- Manifest: `{args.manifest}`",
        f"- Outline ref: `{summary['outline_release_ref']}`",
        f"- Micro root: `{args.micro_root}`",
        f"- **Exact: {exact}/{len(results)} ({summary['exact_rate']:.2%})**",
        f"- Located (any type): {located}",
        f"- Elapsed: {summary['elapsed_sec']}s",
        "",
        "## By category",
        "",
        "| category | exact | total | rate |",
        "|----------|------:|------:|-----:|",
    ]
    for c in EIGHT:
        b = summary["by_category"][c]
        md.append(f"| {c} | {b['exact']} | {b['total']} | {b['rate']:.2%} |")
    md += [
        "",
        "## By status",
        "",
        "| status | n |",
        "|--------|--:|",
    ]
    for k, v in sorted(by_status.items()):
        md.append(f"| {k} | {v} |")
    md += [
        "",
        "## How to re-run",
        "",
        "```bash",
        "python3 scripts/run-typeevalpy-micro-progress.py \\",
        "  --manifest ../outline/outline/src/test/java/org/twelve/outline/toplas/TYPEEVALPY-FACT-MANIFEST.csv \\",
        "  --micro-root \"$TYPEEVALPY_MICRO_ROOT\"",
        "```",
        "",
        "See also `typeevalpy-adapter/` for the Docker harness drop-in (pysonar2-shaped).",
        "",
    ]
    args.out_md.write_text("\n".join(md), encoding="utf-8")

    print(json.dumps(summary, indent=2))
    print(f"wrote {args.out_json}")
    print(f"wrote {args.out_md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
