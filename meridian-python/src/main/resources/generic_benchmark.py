#!/usr/bin/env python3
"""
generic_benchmark.py — Three-way performance comparison for any module:

  CPython(bare)  vs  mypyc(bare, no annotations)  vs  mypyc(GCP-annotated)

Usage:
    python3 generic_benchmark.py <work_dir> <bare_module> <annotated_module> <cases_json>

    work_dir         – directory containing all .py and .so files
    bare_module      – name of the original, zero-annotation .py
    annotated_module – name of the GCP-annotated .py and its compiled .so
    cases_json       – JSON array of [func_name, args_list, iterations], e.g.:
                       '[["sum_range",[1000],500000],["count_multiples",[1000,7],500000]]'

Output: JSON object to stdout.
Each row: func, cpython_ns, mypyc_bare_ns, mypyc_gcp_ns,
          speedup_bare (cpython / mypyc_bare),
          speedup_gcp  (cpython / mypyc_gcp),
          cv_gcp_pct   (coefficient of variation % of the 5 GCP timing samples).
"""

import importlib.util
import json
import math
import os
import sys
import time


# ── module loaders ─────────────────────────────────────────────────────────────

def _load_py(path: str, alias: str):
    spec = importlib.util.spec_from_file_location(alias, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _load_so(so_path: str, module_name: str):
    spec = importlib.util.spec_from_file_location(module_name, so_path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = mod
    spec.loader.exec_module(mod)
    return mod


def _find_so(directory: str, module_prefix: str) -> str | None:
    candidates = [
        f for f in os.listdir(directory)
        if f.startswith(module_prefix) and (f.endswith(".so") or f.endswith(".pyd"))
    ]
    return os.path.join(directory, candidates[0]) if candidates else None


# ── micro-benchmark ────────────────────────────────────────────────────────────

def _bench(fn, args: tuple, iters: int) -> tuple[float, float]:
    """Return (median_ns, cv_pct) over iters hot iterations (after warm-up).

    cv_pct = coefficient of variation = stddev/mean * 100 across the 5 samples.
    """
    warmup = min(iters // 10, 5_000)
    for _ in range(warmup):
        fn(*args)
    chunk = max(iters // 5, 1)
    samples = []
    for _ in range(5):
        t0 = time.perf_counter()
        for _ in range(chunk):
            fn(*args)
        samples.append((time.perf_counter() - t0) / chunk * 1e9)
    samples.sort()
    median = samples[2]
    mean = sum(samples) / len(samples)
    variance = sum((s - mean) ** 2 for s in samples) / len(samples)
    cv_pct = (math.sqrt(variance) / mean * 100) if mean > 0 else 0.0
    return median, round(cv_pct, 2)


# ── main ───────────────────────────────────────────────────────────────────────

def main():
    if len(sys.argv) != 5:
        print(json.dumps({"error":
              "usage: generic_benchmark.py <work_dir> <bare_module> <annotated_module> <cases_json>"}),
              file=sys.stderr)
        sys.exit(1)

    work_dir, bare_mod, ann_mod, cases_json = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

    try:
        cases = json.loads(cases_json)
    except json.JSONDecodeError as e:
        print(json.dumps({"error": f"invalid cases_json: {e}"}), file=sys.stderr)
        sys.exit(1)

    # ── locate files ──────────────────────────────────────────────────────────
    bare_py = os.path.join(work_dir, bare_mod + ".py")
    ann_py  = os.path.join(work_dir, ann_mod  + ".py")

    for path, label in [(bare_py, "bare .py"), (ann_py, "annotated .py")]:
        if not os.path.isfile(path):
            print(json.dumps({"error": f"{label} not found: {path}"}), file=sys.stderr)
            sys.exit(1)

    bare_so = _find_so(work_dir, bare_mod)
    ann_so  = _find_so(work_dir, ann_mod)

    if bare_so is None:
        print(json.dumps({"error": f"no .so for bare module '{bare_mod}' in {work_dir}"}), file=sys.stderr)
        sys.exit(1)
    if ann_so is None:
        print(json.dumps({"error": f"no .so for annotated module '{ann_mod}' in {work_dir}"}), file=sys.stderr)
        sys.exit(1)

    # ── load all three versions ───────────────────────────────────────────────
    py_mod      = _load_py(bare_py, bare_mod + "__cpython")
    bare_so_mod = _load_so(bare_so, bare_mod)
    ann_so_mod  = _load_so(ann_so,  ann_mod)

    # ── run benchmarks ────────────────────────────────────────────────────────
    rows = []
    for entry in cases:
        fn_name, args_list, iters = entry[0], entry[1], entry[2]
        args = tuple(args_list)

        py_fn   = getattr(py_mod,      fn_name, None)
        bare_fn = getattr(bare_so_mod, fn_name, None)
        ann_fn  = getattr(ann_so_mod,  fn_name, None)

        if py_fn is None or bare_fn is None or ann_fn is None:
            missing = [n for n, f in [(bare_mod, bare_fn), (ann_mod, ann_fn), ("cpython", py_fn)] if f is None]
            rows.append({"func": f"{fn_name}({', '.join(str(a) for a in args)})",
                         "error": f"function not found in: {missing}"})
            continue

        # Verify correctness: all three versions must return the same result
        correct = False
        try:
            py_result   = py_fn(*args)
            bare_result = bare_fn(*args)
            ann_result  = ann_fn(*args)
            if py_result != bare_result or py_result != ann_result:
                rows.append({"func": fn_name, "correct": False, "error":
                    f"result mismatch: cpython={py_result}, bare={bare_result}, gcp={ann_result}"})
                continue
            correct = True
        except Exception as e:
            rows.append({"func": fn_name, "correct": False, "error": f"execution error: {e}"})
            continue

        cpython_ns, cv_cpython = _bench(py_fn,   args, iters)
        bare_ns,    cv_bare    = _bench(bare_fn, args, iters)
        gcp_ns,     cv_gcp     = _bench(ann_fn,  args, iters)

        rows.append({
            "func":          f"{fn_name}({', '.join(str(a) for a in args)})",
            "correct":       correct,
            "cpython_ns":    round(cpython_ns, 1),
            "mypyc_bare_ns": round(bare_ns,    1),
            "mypyc_gcp_ns":  round(gcp_ns,     1),
            "speedup_bare":  round(cpython_ns / bare_ns if bare_ns > 0 else 0.0, 2),
            "speedup_gcp":   round(cpython_ns / gcp_ns  if gcp_ns  > 0 else 0.0, 2),
            "cv_cpython_pct": cv_cpython,
            "cv_bare_pct":    cv_bare,
            "cv_gcp_pct":     cv_gcp,
        })

    print(json.dumps({"rows": rows}))


if __name__ == "__main__":
    main()
