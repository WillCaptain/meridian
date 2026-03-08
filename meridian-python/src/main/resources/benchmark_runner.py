#!/usr/bin/env python3
"""
benchmark_runner.py  —  Compare pure CPython vs mypyc-compiled execution speed.

Usage:
    python3 benchmark_runner.py <py_dir> <so_dir> <module_name>

    py_dir      – directory containing <module_name>.py  (interpreted)
    so_dir      – directory containing <module_name>.*.so  (mypyc-compiled)
    module_name – e.g. "math_utils"

Output: one JSON object printed to stdout, containing a "rows" array.
Each row has: func, py_ns, mypyc_ns, speedup.
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
    """
    Load a mypyc-compiled extension by its absolute path.
    The alias *must* be the bare module name (e.g. "math_utils") because
    mypyc's internal init function is named  PyInit_<module_name>.
    """
    spec = importlib.util.spec_from_file_location(module_name, so_path)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[module_name] = mod          # required for mypyc cross-module refs
    spec.loader.exec_module(mod)
    return mod


# ── micro-benchmark ───────────────────────────────────────────────────────────

def _bench(fn, args: tuple, iters: int) -> float:
    """Return median ns/call over `iters` hot iterations (after a warm-up pass)."""
    warmup = min(iters // 10, 2_000)
    for _ in range(warmup):
        fn(*args)
    # Split into 5 equal runs, take the median to reduce noise
    chunk = iters // 5
    samples = []
    for _ in range(5):
        t0 = time.perf_counter()
        for _ in range(chunk):
            fn(*args)
        samples.append((time.perf_counter() - t0) / chunk * 1e9)
    samples.sort()
    return samples[2]   # median


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
        print(json.dumps({"error": "usage: benchmark_runner.py <py_dir> <so_dir> <module>"}),
              file=sys.stderr)
        sys.exit(1)

    py_dir, so_dir, mod_name = sys.argv[1], sys.argv[2], sys.argv[3]

    # Locate files
    py_path = os.path.join(py_dir, mod_name + ".py")
    if not os.path.isfile(py_path):
        print(json.dumps({"error": f"Python source not found: {py_path}"}), file=sys.stderr)
        sys.exit(1)

    so_candidates = [
        f for f in os.listdir(so_dir)
        if f.startswith(mod_name) and (f.endswith(".so") or f.endswith(".pyd"))
    ]
    if not so_candidates:
        print(json.dumps({"error": f"No .so/.pyd found in {so_dir} for '{mod_name}'"}),
              file=sys.stderr)
        sys.exit(1)
    so_path = os.path.join(so_dir, so_candidates[0])

    # Load both versions
    py_mod = _load_py(py_path, mod_name + "__py")
    so_mod = _load_so(so_path, mod_name)

    # Run benchmarks
    rows = []
    for fn_name, args, iters in CASES:
        py_fn = getattr(py_mod, fn_name, None)
        so_fn = getattr(so_mod, fn_name, None)
        if py_fn is None or so_fn is None:
            continue

        py_ns   = _bench(py_fn, args, iters)
        mypyc_ns = _bench(so_fn, args, iters)
        speedup = py_ns / mypyc_ns if mypyc_ns > 0 else 0.0

        rows.append({
            "func":     f"{fn_name}({', '.join(str(a) for a in args)})",
            "py_ns":    round(py_ns, 1),
            "mypyc_ns": round(mypyc_ns, 1),
            "speedup":  round(speedup, 2),
        })

    print(json.dumps({"rows": rows}))


if __name__ == "__main__":
    main()
