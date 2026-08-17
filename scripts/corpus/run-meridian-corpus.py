#!/usr/bin/env python3
"""Score Meridian (native Python) on the §8.7 real-world corpus fact manifest.

Oracle-free: facts are paired to Meridian `sites` output by exact
(file, line, col, kind, symbol) key only. Expected types are used solely in the
final predicted-vs-expected comparison.
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
from pathlib import Path

MERIDIAN_ROOT = Path(__file__).resolve().parents[2]
MERIDIAN_BIN = MERIDIAN_ROOT / "bin" / "meridian"


def standardize(t: str) -> str:
    x = t.strip()
    aliases = {
        "None": "Nonetype",
        "NoneType": "Nonetype",
        "none": "Nonetype",
    }
    return aliases.get(x, x)


def parse_expected(s: str) -> set[str]:
    if not s:
        return set()
    parts = [p.strip() for p in s.replace("|", ";").split(";") if p.strip()]
    return {standardize(p) for p in parts}


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
        fact["kind"],
        fact["symbol"],
    )


def types_of(site: dict | None) -> set[str]:
    if not site:
        return set()
    return {standardize(t) for t in site.get("type", [])}


def run_meridian_sites(py_file: Path) -> list[dict]:
    cmd = [str(MERIDIAN_BIN), "sites", str(py_file)]
    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        cwd=str(MERIDIAN_ROOT),
        timeout=180,
    )
    if proc.returncode != 0:
        raise RuntimeError(f"meridian sites failed ({proc.returncode}): {proc.stderr[-1000:]}")
    out = proc.stdout.strip()
    if not out:
        return []
    data = json.loads(out)
    return data if isinstance(data, list) else []


def score_fact(fact: dict, site: dict | None) -> dict:
    exp = parse_expected(fact["expected_types"])
    pred = types_of(site)
    if site is None:
        status = "MISS_LOCATION"
    elif pred == exp:
        status = "EXACT"
    else:
        status = "MISS_TYPE"
    return {
        "fact_id": fact["fact_id"],
        "project": fact["project"],
        "status": status,
        "expected": sorted(exp),
        "predicted": sorted(pred),
    }


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--manifest", required=True, type=Path)
    p.add_argument("--corpus-root", required=True, type=Path)
    p.add_argument("--out", required=True, type=Path)
    args = p.parse_args()

    if not MERIDIAN_BIN.is_file():
        print(f"missing {MERIDIAN_BIN}", file=sys.stderr)
        return 2

    facts = list(csv.DictReader(args.manifest.open(newline="", encoding="utf-8")))
    if not facts:
        print("empty manifest", file=sys.stderr)
        return 2

    # Group facts by (project, file) so each source file is inferred once.
    by_file: dict[tuple[str, str], list[dict]] = {}
    for f in facts:
        by_file.setdefault((f["project"], f["file"]), []).append(f)

    site_index: dict[tuple, dict] = {}
    file_status: dict[tuple[str, str], str] = {}
    for (project, rel) in sorted(by_file):
        py = args.corpus_root / project / rel
        if not py.is_file():
            file_status[(project, rel)] = "MISSING_SOURCE"
            continue
        try:
            for s in run_meridian_sites(py):
                s = dict(s)
                s["file"] = rel  # fact manifest uses repo-relative path
                site_index[site_key_fields(s)] = s
            file_status[(project, rel)] = "OK"
        except Exception as e:  # noqa: BLE001
            file_status[(project, rel)] = f"ERROR: {e}"[:200]

    results = []
    for fact in facts:
        key = (fact["project"], fact["file"])
        if file_status[key] != "OK":
            r = score_fact(fact, None)
            r["status"] = "ERROR" if file_status[key] != "MISSING_SOURCE" else "MISSING_SOURCE"
            r["file_status"] = file_status[key]
        else:
            site = site_index.get(fact_key_fields(fact))
            r = score_fact(fact, site)
        results.append(r)

    exact = sum(1 for r in results if r["status"] == "EXACT")
    total = len(results)
    summary = {
        "tool": "meridian-native",
        "scoring": "strict-oracle-free",
        "exact": f"{exact}/{total}",
        "rate": round(100 * exact / total, 2) if total else 0.0,
        "total": total,
        "by_status": {
            s: sum(1 for r in results if r["status"] == s)
            for s in ("EXACT", "MISS_TYPE", "MISS_LOCATION", "MISSING_SOURCE", "ERROR")
        },
        "by_project": {},
    }
    for r in results:
        pr = summary["by_project"].setdefault(r["project"], {"exact": 0, "total": 0})
        pr["total"] += 1
        pr["exact"] += r["status"] == "EXACT"

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(
        json.dumps({"summary": summary, "results": results}, indent=2), encoding="utf-8"
    )
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
