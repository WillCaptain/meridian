#!/usr/bin/env python3
"""Run classical Python type-inference baselines on the §8.7 real-world corpus.

Scores Pyright (LSP hover), Type4Py, and HiTyper against the same fact manifest
as run-meridian-corpus.py. Oracle-free: predictions are gathered per site/name
from each tool's own output; expected types are used only in the final
predicted-vs-expected comparison.

The per-tool output normalizers are intentionally isolated so the exact field
names can be pinned to the installed tool version without touching the
orchestration below. If a tool's format differs, only the *_normalize function
for that tool needs adjustment.
"""

from __future__ import annotations

import argparse
import csv
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

TOOLS = ("pyright", "type4py", "hityper")


def standardize(t: str) -> str:
    x = t.strip()
    aliases = {"None": "Nonetype", "NoneType": "Nonetype", "none": "Nonetype"}
    return aliases.get(x, x)


def parse_expected(s: str) -> set[str]:
    if not s:
        return set()
    return {standardize(p) for p in s.replace("|", ";").split(";") if p.strip()}


# --------------------------------------------------------------------------- #
# Pyright via LSP hover
# --------------------------------------------------------------------------- #

@dataclass
class _LspClient:
    proc: subprocess.Popen
    _id: int = 0

    def request(self, method: str, params: dict) -> dict | None:
        self._id += 1
        msg = {"jsonrpc": "2.0", "id": self._id, "method": method, "params": params}
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()
        while True:
            line = self.proc.stdout.readline()
            if not line:
                raise RuntimeError("LSP server closed stdout")
            resp = json.loads(line)
            if resp.get("id") == self._id:
                return resp.get("result")

    def notify(self, method: str, params: dict) -> None:
        msg = {"jsonrpc": "2.0", "method": method, "params": params}
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()


def _pyright_hover_type(markdown: str, kind: str) -> set[str]:
    """Extract a closed-world type set from a pyright hover markdown block.

    Handles the common forms:
      (variable) x: int
      (function) def foo(a: int) -> str
      (parameter) a: int
    Returns the empty set when no type can be read.
    """
    text = markdown.replace("```python", "").replace("```", "").strip()
    first = text.splitlines()[0] if text else ""
    # Return-arrow form (function signature) — take the type after "->".
    if "->" in first:
        rhs = first.split("->", 1)[1].strip()
        return {standardize(rhs)}
    # "name: type" form — take the last ":" segment.
    if ":" in first:
        rhs = first.rsplit(":", 1)[1].strip()
        return {standardize(rhs)}
    # Bare form — treat the whole trimmed value as the type.
    return {standardize(first)} if first else set()


def run_pyright(corpus_root: Path, facts: list[dict]) -> dict[tuple[str, int, int], set[str]]:
    """Return {(file, line_1based, col_0based): types} via LSP hover.

    Opens every source file once, then hovers at each fact's locator. Pyright
    analyses asynchronously after didOpen; if hovers come back null on a cold
    session, add a settle delay after the didOpen loop (version dependent).
    Column is passed as-is: the manifest column is 0-based and LSP uses 0-based
    UTF-16 columns, which coincide for the ASCII identifiers this corpus targets.
    """
    out: dict[tuple[str, int, int], set[str]] = {}
    cmd = ["pyright-langserver", "--stdio"]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    cli = _LspClient(proc)
    try:
        cli.request(
            "initialize",
            {
                "processId": None,
                "rootUri": corpus_root.as_uri(),
                "capabilities": {"textDocument": {"hover": {"contentFormat": ["markdown"]}}},
            },
        )
        cli.notify("initialized", {})

        files = sorted({corpus_root / f["project"] / f["file"] for f in facts})
        for py in files:
            uri = py.as_uri()
            cli.notify(
                "textDocument/didOpen",
                {"textDocument": {"uri": uri, "languageId": "python", "version": 1,
                                  "text": py.read_text(encoding="utf-8")}},
            )

        for f in facts:
            py = corpus_root / f["project"] / f["file"]
            key = (f["file"], int(f["line"]), int(f["column"]))
            result = cli.request(
                "textDocument/hover",
                {
                    "textDocument": {"uri": py.as_uri()},
                    "position": {"line": int(f["line"]) - 1, "character": int(f["column"])},
                },
            )
            if not result:
                continue
            contents = result.get("contents")
            markdown = ""
            if isinstance(contents, dict):
                markdown = contents.get("value", "")
            elif isinstance(contents, list):
                markdown = "\n".join(
                    c.get("value", c) if isinstance(c, dict) else str(c) for c in contents
                )
            elif isinstance(contents, str):
                markdown = contents
            types = _pyright_hover_type(markdown, f["kind"]) if markdown else set()
            if types:
                out[key] = types
    finally:
        cli.request("shutdown", {})
        cli.notify("exit", {})
        proc.terminate()
    return out


def _normalize_type4py(raw: dict) -> dict[str, set[str]]:
    """Map Type4Py `predict` JSON to {name: types}. Field names are version
    dependent; adjust to the installed release here."""
    out: dict[str, set[str]] = {}
    for key in ("variables", "params", "returns"):
        block = raw.get(key, {})
        if not isinstance(block, dict):
            continue
        for name, val in block.items():
            if isinstance(val, list):
                out[name] = {standardize(t) for t in val}
            elif isinstance(val, dict) and "type" in val:
                out[name] = {standardize(str(val["type"]))}
    return out


def _normalize_hityper(raw) -> dict[str, set[str]]:
    """Map HiTyper `infer` JSON to {name: types}. Field names are version
    dependent; adjust to the installed release here."""
    out: dict[str, set[str]] = {}
    records = raw if isinstance(raw, list) else raw.get("types", raw.get("variables", []))
    if not isinstance(records, list):
        return out
    for r in records:
        if not isinstance(r, dict):
            continue
        name = r.get("name") or r.get("var") or r.get("variable")
        if not name:
            continue
        ty = r.get("type")
        if isinstance(ty, list):
            out[str(name)] = {standardize(t) for t in ty}
        elif ty is not None:
            out[str(name)] = {standardize(str(ty))}
    return out


# --------------------------------------------------------------------------- #
# Orchestration
# --------------------------------------------------------------------------- #

def score(name_keyed: dict[str, set[str]], fact: dict) -> dict:
    exp = parse_expected(fact["expected_types"])
    pred = name_keyed.get(fact["symbol"], set())
    status = "MISS_LOCATION" if not pred else ("EXACT" if pred == exp else "MISS_TYPE")
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
    p.add_argument("--tools", default="pyright,type4py,hityper")
    p.add_argument("--out", required=True, type=Path)
    args = p.parse_args()

    tools = [t.strip() for t in args.tools.split(",") if t.strip()]
    for t in tools:
        if t not in TOOLS:
            print(f"unknown tool {t} (choose from {','.join(TOOLS)})", file=sys.stderr)
            return 2

    facts = list(csv.DictReader(args.manifest.open(newline="", encoding="utf-8")))
    if not facts:
        print("empty manifest", file=sys.stderr)
        return 2

    results: dict[str, list[dict]] = {t: [] for t in tools}

    # Pyright: locator-keyed hover over each fact's own (line, col).
    if "pyright" in tools:
        loc = run_pyright(args.corpus_root, facts)
        for f in facts:
            pred = loc.get((f["file"], int(f["line"]), int(f["column"])), set())
            exp = parse_expected(f["expected_types"])
            status = "MISS_LOCATION" if not pred else ("EXACT" if pred == exp else "MISS_TYPE")
            results["pyright"].append({
                "fact_id": f["fact_id"], "project": f["project"], "status": status,
                "expected": sorted(exp), "predicted": sorted(pred),
            })

    # Type4Py / HiTyper: name-keyed predictions per file.
    for tool in ("type4py", "hityper"):
        if tool not in tools:
            continue
        by_file: dict[tuple[str, str], list[dict]] = {}
        for f in facts:
            by_file.setdefault((f["project"], f["file"]), []).append(f)
        for (project, rel), fgroup in sorted(by_file.items()):
            py = args.corpus_root / project / rel
            if not py.is_file():
                for f in fgroup:
                    results[tool].append({
                        "fact_id": f["fact_id"], "project": f["project"],
                        "status": "MISSING_SOURCE", "expected": sorted(parse_expected(f["expected_types"])),
                        "predicted": [],
                    })
                continue
            name_keyed: dict[str, set[str]] = {}
            try:
                if tool == "type4py":
                    cmd = ["type4py", "predict", "--o", "-", "--c", str(py)]
                    raw = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
                    name_keyed = _normalize_type4py(json.loads(raw.stdout or "{}"))
                else:
                    cmd = ["hityper", "infer", "--file", str(py)]
                    raw = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
                    name_keyed = _normalize_hityper(json.loads(raw.stdout or "[]"))
            except Exception as e:  # noqa: BLE001
                for f in fgroup:
                    results[tool].append({
                        "fact_id": f["fact_id"], "project": f["project"], "status": "ERROR",
                        "error": str(e)[:200], "expected": sorted(parse_expected(f["expected_types"])),
                        "predicted": [],
                    })
                continue
            for f in fgroup:
                results[tool].append(score(name_keyed, f))

    summary = {}
    for tool, rs in results.items():
        exact = sum(1 for r in rs if r["status"] == "EXACT")
        total = len(rs)
        summary[tool] = {
            "exact": f"{exact}/{total}",
            "rate": round(100 * exact / total, 2) if total else 0.0,
            "total": total,
        }

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps({"summary": summary, "results": results}, indent=2), encoding="utf-8")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
