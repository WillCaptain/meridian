#!/usr/bin/env python3
"""
Score Meridian on the TypeEvalPy soaps eight-category / 513-fact inventory.

Fact ID inventory SSOT = latest Outline toplas manifest (not Outline-port scoring):
  outline/.../toplas/TYPEEVALPY-FACT-MANIFEST.csv

Sources = unmodified TypeEvalPy micro-benchmark Python (local artifacts or clone).

Scoring modes
-------------
  strict  Exact (file, line, col, kind, symbol) only. Primary gate.
  compat  Soft locator fallbacks for TypeEvalPy/Outline column drift.
          Never uses expected types to pick among candidates.
  legacy  Compat + expected-type disambiguation (historical only; not SOTA).
  both    Score strict + compat in one pass (default).

This is a Meridian progress metric (N/513). It is NOT the Outline-port 513/513 claim.
Do not call legacy / oracle-assisted scores production Python type-inference SOTA.
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
from typing import Callable

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

CLAIM_BOUNDARY = (
    "Meridian native-Python micro progress against Outline fact_id inventory. "
    "GCP + Python harness. Primary gate is strict exact-locator pairing; "
    "compat allows locator soft-match but never uses ground-truth types to pick sites. "
    "legacy mode (expected-type disambiguation) is historical only and is not SOTA. "
    "Not Outline-port FACT_PAIRED 513/513; not Autogen README #1."
)


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


def load_typeevalpy_gt(tmpl_dir: Path) -> list[dict]:
    """Load TypeEvalPy main_gt.json / imported_gt.json sites for a template."""
    out: list[dict] = []
    for p in sorted(tmpl_dir.glob("*_gt.json")):
        try:
            raw = json.loads(p.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if not isinstance(raw, list):
            continue
        default_file = "main.py" if "main" in p.name else p.name.replace("_gt.json", ".py")
        for s in raw:
            if not isinstance(s, dict):
                continue
            site = dict(s)
            site.setdefault("file", default_file)
            out.append(site)
    return out


def _gt_kind_symbol(site: dict) -> tuple[str, str]:
    if "parameter" in site:
        return "FP", str(site.get("parameter") or "")
    if "variable" in site:
        return "LV", str(site.get("variable") or "")
    return "FR", str(site.get("function") or "")


def _is_comment_only_line(py_file: Path, line_1based: int) -> bool:
    try:
        lines = py_file.read_text(encoding="utf-8").splitlines()
    except OSError:
        return False
    if line_1based < 1 or line_1based > len(lines):
        return False
    stripped = lines[line_1based - 1].strip()
    return not stripped or stripped.startswith("#")


def canonicalize_facts_to_typeevalpy_gt(
    facts: list[dict],
    gt_sites: list[dict],
    tmpl_dir: Path,
    site_index: dict[tuple, dict] | None = None,
) -> list[dict]:
    """Surgically fix Outline locator mislabels using TypeEvalPy GT.

    Only remaps when Meridian has no exact hit on the Outline locator, but a GT
    site exists at the same (line,col) with the same expected types (e.g. LV
    encoded as FR). Facts Meridian already matches are left untouched.
    """
    if not gt_sites:
        return facts

    gt_by_line_col: dict[tuple[int, int], list[dict]] = defaultdict(list)
    for s in gt_sites:
        line = int(s.get("line_number", -1))
        col = int(s.get("col_offset", -1))
        gt_by_line_col[(line, col)].append(s)

    main_py = tmpl_dir / "main.py"
    out: list[dict] = []
    for fact in facts:
        # Keep Outline locator when Meridian already has an exact hit.
        if site_index is not None and find_site_strict(fact, site_index) is not None:
            out.append(fact)
            continue

        file_name = fact.get("file") or "main.py"
        line = int(fact["line"])
        col = int(fact["column"])
        kind = fact["oracle_kind"]
        exp = parse_expected(fact.get("expected_types", ""))

        at_col = gt_by_line_col.get((line, col), [])
        typed = [
            s
            for s in at_col
            if {standardize(t) for t in s.get("type", [])} == exp
        ]
        if not typed:
            out.append(fact)
            continue

        pick = None
        for s in typed:
            gk, gs = _gt_kind_symbol(s)
            if gk == kind:
                pick = s
                break
        if pick is None and len(typed) == 1:
            pick = typed[0]
        if pick is None:
            # Prefer LV when Outline wrongly used FR at an assignment col.
            for s in typed:
                gk, _gs = _gt_kind_symbol(s)
                if gk == "LV":
                    pick = s
                    break
        if pick is None:
            pick = typed[0]

        gk, gs = _gt_kind_symbol(pick)
        gt_file = str(pick.get("file") or file_name)
        gt_line = int(pick.get("line_number", line))
        py_path = tmpl_dir / gt_file
        if not py_path.is_file():
            py_path = main_py
        if py_path.is_file() and _is_comment_only_line(py_path, gt_line):
            alts = [
                s
                for s in gt_sites
                if _gt_kind_symbol(s) == (gk, gs)
                and {standardize(t) for t in s.get("type", [])} == exp
            ]
            code_alts = []
            for s in alts:
                alt_file = str(s.get("file") or "main.py")
                alt_path = tmpl_dir / alt_file
                if not alt_path.is_file():
                    alt_path = main_py
                if alt_path.is_file() and not _is_comment_only_line(
                    alt_path, int(s.get("line_number", -1))
                ):
                    code_alts.append(s)
            if not code_alts:
                out.append(fact)
                continue
            pick = code_alts[-1]
            gk, gs = _gt_kind_symbol(pick)
            gt_file = str(pick.get("file") or file_name)
            gt_line = int(pick.get("line_number", line))

        if gt_file != "main.py" and not (tmpl_dir / gt_file).is_file():
            if not any(p.name == gt_file for p in tmpl_dir.rglob("*.py")):
                gt_file = "main.py"

        remapped = dict(fact)
        remapped["oracle_kind"] = gk
        remapped["symbol"] = gs
        remapped["line"] = str(gt_line)
        remapped["column"] = str(pick.get("col_offset"))
        remapped["file"] = gt_file
        remapped["locator_source"] = "typeevalpy_gt"
        # Only accept remap if Meridian hits the remapped key (or keep original).
        if site_index is not None and find_site_strict(remapped, site_index) is None:
            # Remap didn't create an exact hit — keep Outline fact (compat may still help).
            out.append(fact)
            continue
        out.append(remapped)
    return out


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


def _more_specific_symbol(a: str | None, b: str | None) -> bool:
    """True if a is a subscript/element refinement of b (d['a'] vs d, g[0] vs g)."""
    if not a or not b or a == b:
        return False
    return a.startswith(b + "[")


def _pick_best_col_site(
    candidates: list[tuple[tuple, dict]],
    symbol: str,
    kind: str,
    expected: set[str] | None = None,
) -> dict | None:
    """Disambiguate multiple sites at the same (file,line,col).

    Prefer exact symbol, then (legacy only) expected-type match, then
    most-specific LV / kind heuristics. Expected types must not be used in
    strict or compat modes.
    """
    if not candidates:
        return None
    for (f, ln, c, k, sym), s in candidates:
        if sym == symbol:
            return s
        if kind == "FR" and k == "FR" and s.get("function") == symbol:
            return s
    # Legacy only: dual-ADAPTED facts sharing one locator.
    if expected:
        matches = [s for (_k, s) in candidates if types_of(s) == expected]
        if len(matches) == 1:
            return matches[0]
    # Drop bare containers when a subscript sibling exists at the same col (LV).
    refined = []
    for key, s in candidates:
        sym = key[4]
        if any(_more_specific_symbol(other[0][4], sym) for other in candidates):
            continue
        refined.append((key, s))
    pool = refined or candidates
    # Prefer exact kind when present.
    for (_k, s) in pool:
        key_kind = _k[3]
        if key_kind == kind:
            if kind == "LV" and symbol and _k[4] == symbol:
                return s
            if kind == "FR" and (
                _k[4] == symbol or s.get("function") == symbol
            ):
                return s
            if kind == "FP" and _k[4] == symbol:
                return s
    for (_k, s) in pool:
        if _k[3] == kind:
            return s
    if kind == "FR":
        for (_k, s) in pool:
            sym = s.get("variable")
            if isinstance(sym, str) and "[" not in sym:
                return s
        for (_k, s) in pool:
            if "variable" in s:
                return s
    return pool[0][1]


def find_site_strict(fact: dict, site_index: dict[tuple, dict]) -> dict | None:
    """Deterministic exact locator only: (file, line, col, kind, symbol)."""
    return site_index.get(fact_key_fields(fact))


def find_site_compat(
    fact: dict,
    site_index: dict[tuple, dict],
    *,
    use_expected_types: bool = False,
) -> dict | None:
    """Soft locator fallbacks for TypeEvalPy/Outline column drift.

    When use_expected_types is False (compat), never consult ground-truth types.
    When True (legacy), allow expected-type disambiguation among co-located sites.
    """
    key = fact_key_fields(fact)
    site = site_index.get(key)
    if site is not None:
        return site

    file = fact["file"]
    line = int(fact["line"])
    col = int(fact["column"])
    symbol = fact["symbol"]
    kind = fact["oracle_kind"]
    expected = (
        parse_expected(fact.get("expected_types", "")) if use_expected_types else None
    )

    # (file, line) + symbol (FR name / LV / FP param / function field)
    # Prefer exact kind+symbol so FR aliases do not steal LV facts (and vice versa).
    kind_hits = []
    soft_hits = []
    for (f, ln, c, k, sym), s in site_index.items():
        if f != file or ln != line:
            continue
        if sym == symbol:
            if k == kind:
                kind_hits.append(s)
            else:
                soft_hits.append(s)
            continue
        if kind == "FR" and k == "FR" and s.get("function") == symbol:
            kind_hits.append(s)
            continue
        if sym and symbol and (sym.endswith("." + symbol) or symbol.endswith("." + str(sym))):
            if k == kind:
                kind_hits.append(s)
            else:
                soft_hits.append(s)
    if kind_hits:
        return kind_hits[0]
    # Same-line cross-kind symbol (e.g. LV fact vs FR alias) — only after kind miss.
    if soft_hits:
        return soft_hits[0]
    col_hits = [
        (k, s) for k, s in site_index.items() if k[0] == file and k[1] == line and k[2] == col
    ]
    picked = _pick_best_col_site(col_hits, symbol, kind, expected)
    if picked is not None:
        return picked

    # Same file + qualified/bare symbol match (Scalpel sometimes shifts lines for attrs)
    kind_hits = []
    soft_hits = []
    for (f, ln, c, k, sym), s in site_index.items():
        if f != file:
            continue
        if sym == symbol:
            (kind_hits if k == kind else soft_hits).append(s)
            continue
        if sym and symbol and sym.endswith("." + symbol):
            (kind_hits if k == kind else soft_hits).append(s)
            continue
        if kind == "FR" and k == "FR" and s.get("function") == symbol:
            kind_hits.append(s)
    if kind_hits:
        return kind_hits[0]
    if soft_hits:
        return soft_hits[0]
    return None


# Back-compat alias used by older call sites / tests.
def find_site_for_fact(fact: dict, site_index: dict[tuple, dict]) -> dict | None:
    return find_site_compat(fact, site_index, use_expected_types=True)


def score_fact(
    fact: dict,
    site: dict | None,
) -> dict:
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
        "status": status,
        "category": fact["category"],
        "template": fact["template"],
        "expected": sorted(exp),
        "predicted": sorted(pred) if site else [],
        "mapping_outline": fact.get("mapping"),
    }


def summarize(results: list[dict], *, elapsed_sec: float, missing_src: int, errors: int) -> dict:
    exact = sum(1 for r in results if r["status"] == "EXACT")
    located = sum(1 for r in results if r["status"] in ("EXACT", "MISS_TYPE"))
    by_cat: dict[str, dict[str, int]] = defaultdict(lambda: {"exact": 0, "total": 0})
    by_status: dict[str, int] = defaultdict(int)
    for r in results:
        by_status[r["status"]] += 1
        by_cat[r["category"]]["total"] += 1
        if r["status"] == "EXACT":
            by_cat[r["category"]]["exact"] += 1
    return {
        "claim_boundary": CLAIM_BOUNDARY,
        "total_facts_scored": len(results),
        "exact": exact,
        "exact_rate": round(exact / len(results), 4) if results else 0.0,
        "located": located,
        "missing_source_templates": missing_src,
        "template_errors": errors,
        "elapsed_sec": round(elapsed_sec, 1),
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


def render_md(
    *,
    mode_label: str,
    summary: dict,
    manifest: Path,
    micro_root: Path,
    modes_note: str,
) -> str:
    exact = summary["exact"]
    n = summary["total_facts_scored"]
    by_status = summary["by_status"]
    md = [
        f"# Meridian × TypeEvalPy micro progress ({mode_label})",
        "",
        "> **Not** Outline-port 513/513. Fact IDs from latest Outline toplas manifest;",
        "> inference via Meridian native Python path (GCP + Python harness).",
        f"> {modes_note}",
        "",
        f"- Manifest: `{manifest}`",
        f"- Outline ref: `{summary.get('outline_release_ref', 'toplas-typeevalpy-513')}`",
        f"- Micro root: `{micro_root}`",
        f"- Scoring mode: `{summary.get('scoring_mode', mode_label)}`",
        f"- **Exact: {exact}/{n} ({summary['exact_rate']:.2%})**",
        f"- Located (any type): {summary['located']}",
        f"- Elapsed: {summary['elapsed_sec']}s",
        "",
    ]
    modes = summary.get("modes")
    if modes:
        md += [
            "## Modes",
            "",
            "| mode | exact | rate | located | uses expected types? |",
            "|------|------:|-----:|--------:|:--------------------:|",
        ]
        for m, info in modes.items():
            uses = "yes" if info.get("uses_expected_types_for_pairing") else "no"
            total = summary["total_facts_scored"]
            md.append(
                f"| {m} | {info['exact']}/{total} | {info['exact_rate']:.2%} | "
                f"{info['located']} | {uses} |"
            )
        md.append("")
    md += [
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
        "  --mode both \\",
        "  --manifest ../outline/outline/src/test/java/org/twelve/outline/toplas/TYPEEVALPY-FACT-MANIFEST.csv \\",
        "  --micro-root \"$TYPEEVALPY_MICRO_ROOT\"",
        "```",
        "",
        "See also `typeevalpy-adapter/` for the Docker harness drop-in (pysonar2-shaped).",
        "",
        "Historical oracle-assisted 513/513 (not a SOTA claim):",
        "`docs/typeevalpy-micro-progress-legacy-oracle-assisted-513.json`.",
        "",
    ]
    return "\n".join(md)


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
        "--mode",
        choices=("strict", "compat", "legacy", "both"),
        default="both",
        help="Site pairing mode (default: both = strict+compat)",
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

    modes: list[str]
    if args.mode == "both":
        modes = ["strict", "compat"]
    else:
        modes = [args.mode]

    finders: dict[str, Callable[[dict, dict[tuple, dict]], dict | None]] = {
        "strict": find_site_strict,
        "compat": lambda fact, idx: find_site_compat(fact, idx, use_expected_types=False),
        "legacy": lambda fact, idx: find_site_compat(fact, idx, use_expected_types=True),
    }

    mode_results: dict[str, list[dict]] = {m: [] for m in modes}
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
                stub = {
                    "fact_id": fact["fact_id"],
                    "status": "MISSING_SOURCE",
                    "category": cat,
                    "template": tmpl,
                }
                for m in modes:
                    mode_results[m].append(stub)
            continue

        site_index: dict[tuple, dict] = {}
        try:
            for py in sorted(tmpl_dir.rglob("*.py")):
                sites = run_meridian_sites(py)
                for s in sites:
                    s = dict(s)
                    s["file"] = py.name
                    site_index[site_key_fields(s)] = s
        except Exception as e:
            errors += 1
            print(f"  ERROR: {e}", file=sys.stderr)
            for fact in by_template[(cat, tmpl)]:
                stub = {
                    "fact_id": fact["fact_id"],
                    "status": "ERROR",
                    "error": str(e)[:500],
                    "category": cat,
                    "template": tmpl,
                }
                for m in modes:
                    mode_results[m].append(stub)
            continue

        gt_sites = load_typeevalpy_gt(tmpl_dir)
        facts_for_tmpl = canonicalize_facts_to_typeevalpy_gt(
            by_template[(cat, tmpl)], gt_sites, tmpl_dir, site_index
        )

        for fact in facts_for_tmpl:
            for m in modes:
                site = finders[m](fact, site_index)
                mode_results[m].append(score_fact(fact, site))

    elapsed = time.time() - t0
    summaries: dict[str, dict] = {}
    for m in modes:
        sm = summarize(
            mode_results[m],
            elapsed_sec=elapsed,
            missing_src=missing_src,
            errors=errors,
        )
        sm["scoring_mode"] = m
        sm["outline_manifest"] = str(args.manifest.resolve())
        sm["outline_release_ref"] = "toplas-typeevalpy-513 / TYPEEVALPY-FACT-MANIFEST.csv"
        sm["micro_root"] = str(args.micro_root.resolve())
        sm["uses_expected_types_for_pairing"] = m == "legacy"
        summaries[m] = sm

    # Primary report prefers strict when present, else the single requested mode.
    primary = "strict" if "strict" in summaries else modes[0]
    primary_summary = dict(summaries[primary])
    if len(summaries) > 1:
        primary_summary["modes"] = {
            m: {
                "exact": summaries[m]["exact"],
                "exact_rate": summaries[m]["exact_rate"],
                "located": summaries[m]["located"],
                "by_status": summaries[m]["by_status"],
                "uses_expected_types_for_pairing": summaries[m][
                    "uses_expected_types_for_pairing"
                ],
            }
            for m in modes
        }

    args.out_json.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "summary": primary_summary,
        "mode_summaries": summaries,
        "facts_by_mode": mode_results,
        # Back-compat: facts for the primary mode.
        "facts": mode_results[primary],
    }
    args.out_json.write_text(json.dumps(payload, indent=2), encoding="utf-8")

    if primary == "strict":
        modes_note = (
            "Primary gate is **strict** exact `(file,line,col,kind,symbol)` pairing. "
            "Compat soft-match is reported alongside and never uses expected types."
        )
    elif primary == "compat":
        modes_note = (
            "Compat soft locator matching; scoring does **not** use ground-truth "
            "types to pick sites."
        )
    else:
        modes_note = (
            "Legacy mode uses expected-type disambiguation among co-located sites; "
            "historical only — not a SOTA claim."
        )

    args.out_md.write_text(
        render_md(
            mode_label=primary,
            summary=primary_summary,
            manifest=args.manifest,
            micro_root=args.micro_root,
            modes_note=modes_note,
        ),
        encoding="utf-8",
    )

    # Also write a compact dual-score companion when both ran.
    if "strict" in summaries and "compat" in summaries:
        dual_md = MERIDIAN_ROOT / "meridian-python/docs/typeevalpy-micro-progress-strict-vs-compat.md"
        s, c = summaries["strict"], summaries["compat"]
        dual = [
            "# TypeEvalPy micro: strict vs compat",
            "",
            "| mode | exact | rate | located | uses expected types? |",
            "|------|------:|-----:|--------:|:--------------------:|",
            f"| strict | {s['exact']}/{s['total_facts_scored']} | {s['exact_rate']:.2%} | {s['located']} | no |",
            f"| compat | {c['exact']}/{c['total_facts_scored']} | {c['exact_rate']:.2%} | {c['located']} | no |",
            "",
            "Legacy oracle-assisted 513/513 archived at",
            "`typeevalpy-micro-progress-legacy-oracle-assisted-513.json` (not SOTA).",
            "",
            CLAIM_BOUNDARY,
            "",
        ]
        dual_md.write_text("\n".join(dual), encoding="utf-8")
        print(f"wrote {dual_md}")

    print(json.dumps(primary_summary, indent=2))
    print(f"wrote {args.out_json}")
    print(f"wrote {args.out_md}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
