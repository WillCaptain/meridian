#!/usr/bin/env python3
"""
benchmark_runner.py  —  Three-way comparison:
  CPython(bare)  vs  mypyc(bare, no annotations)  vs  mypyc(GCP-annotated)

Usage:
    python3 benchmark_runner.py <work_dir> <bare_module> <annotated_module>

    work_dir         – directory containing all .py and .so files
    bare_module      – name of the original, zero-annotation .py
                       (e.g. "math_utils_bare")
    annotated_module – name of the GCP-annotated .py and its compiled .so
                       (e.g. "math_utils_bare_annotated")

Output: one JSON object to stdout with a "rows" array.
Each row: func, cpython_ns, mypyc_bare_ns, mypyc_gcp_ns,
          speedup_bare (mypyc_bare/cpython),
          speedup_gcp  (mypyc_gcp/cpython).
"""

import importlib.util
import json
import os
import sys
import time


# ── module loaders ────────────────────────────────────────────────────────────

def _load_py(path: str, alias: str):
    spec = importlib.util.spec_from_file_location(alias, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def _load_so(so_path: str, module_name: str):
    """Load a mypyc-compiled C extension. The alias must match PyInit_<name>."""
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


# ── micro-benchmark ───────────────────────────────────────────────────────────

def _bench(fn, args: tuple, iters: int) -> float:
    """Median ns/call over iters hot iterations (after warm-up)."""
    warmup = min(iters // 10, 2_000)
    for _ in range(warmup):
        fn(*args)
    chunk = iters // 5
    samples = []
    for _ in range(5):
        t0 = time.perf_counter()
        for _ in range(chunk):
            fn(*args)
        samples.append((time.perf_counter() - t0) / chunk * 1e9)
    samples.sort()
    return samples[2]  # median


# ── benchmark cases ───────────────────────────────────────────────────────────

CASES = [
    # (function_name,  args,           iterations)
    ("factorial",   (10,),          1_000_000),
    ("factorial",   (20,),            500_000),
    ("fibonacci",   (30,),          1_000_000),
    ("sum_squares", (100,),           500_000),
    ("sum_squares", (1_000,),          50_000),
    ("is_prime",    (997,),           500_000),
    ("is_prime",    (9_999_991,),       5_000),
]


# ── main ──────────────────────────────────────────────────────────────────────

def main():
    if len(sys.argv) != 4:
        print(json.dumps({"error":
              "usage: benchmark_runner.py <work_dir> <bare_module> <annotated_module>"}),
              file=sys.stderr)
        sys.exit(1)

    work_dir, bare_mod, ann_mod = sys.argv[1], sys.argv[2], sys.argv[3]

    # ── locate files ─────────────────────────────────────────────────────────
    bare_py = os.path.join(work_dir, bare_mod + ".py")
    if not os.path.isfile(bare_py):
        print(json.dumps({"error": f"bare .py not found: {bare_py}"}), file=sys.stderr)
        sys.exit(1)

    ann_py = os.path.join(work_dir, ann_mod + ".py")
    if not os.path.isfile(ann_py):
        print(json.dumps({"error": f"annotated .py not found: {ann_py}"}), file=sys.stderr)
        sys.exit(1)

    bare_so = _find_so(work_dir, bare_mod)
    ann_so  = _find_so(work_dir, ann_mod)

    if bare_so is None:
        print(json.dumps({"error": f"no .so found for bare module '{bare_mod}' in {work_dir}"}),
              file=sys.stderr)
        sys.exit(1)
    if ann_so is None:
        print(json.dumps({"error": f"no .so found for annotated module '{ann_mod}' in {work_dir}"}),
              file=sys.stderr)
        sys.exit(1)

    # ── load all three versions ───────────────────────────────────────────────
    # 1. CPython interprets the original bare .py
    py_mod        = _load_py(bare_py,  bare_mod + "__cpython")
    # 2. mypyc compiled the bare .py directly (no annotations)
    bare_so_mod   = _load_so(bare_so,  bare_mod)
    # 3. mypyc compiled the GCP-annotated .py
    ann_so_mod    = _load_so(ann_so,   ann_mod)

    # ── run benchmarks ────────────────────────────────────────────────────────
    rows = []
    for fn_name, args, iters in CASES:
        py_fn   = getattr(py_mod,      fn_name, None)
        bare_fn = getattr(bare_so_mod, fn_name, None)
        ann_fn  = getattr(ann_so_mod,  fn_name, None)
        if py_fn is None or bare_fn is None or ann_fn is None:
            continue

        cpython_ns   = _bench(py_fn,   args, iters)
        bare_ns      = _bench(bare_fn, args, iters)
        gcp_ns       = _bench(ann_fn,  args, iters)

        rows.append({
            "func":          f"{fn_name}({', '.join(str(a) for a in args)})",
            "cpython_ns":    round(cpython_ns, 1),
            "mypyc_bare_ns": round(bare_ns,    1),
            "mypyc_gcp_ns":  round(gcp_ns,     1),
            "speedup_bare":  round(cpython_ns / bare_ns if bare_ns > 0 else 0.0, 2),
            "speedup_gcp":   round(cpython_ns / gcp_ns  if gcp_ns  > 0 else 0.0, 2),
        })

    print(json.dumps({"rows": rows}))


if __name__ == "__main__":
    main()
