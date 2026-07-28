#!/usr/bin/env python3
"""
generic_benchmark.py — Evaluate Meridian-compiled code against native CPython.

  1) Load native (CPython) from naked .py
  2) Check eval result: Meridian(.so) == native
  3) Check eval performance: speedup_vs_native = native_ns / meridian_ns

Optional control lane: mypyc(bare) when a bare .so is present.

Usage:
    python3 generic_benchmark.py <work_dir> <native_module> <meridian_module> <cases_json>

    work_dir         – directory containing .py / .so files
    native_module    – naked library module name (CPython baseline; .py required)
    meridian_module  – Meridian-annotated module name (.py + .so required)
    cases_json       – JSON array of [func_name, args_list, iterations]

Exit code:
    0 — every case correct
    1 — setup error or any correctness / execution failure

Dependency isolation: helper .so files must not leak into the native lane.
"""

import importlib.util
import json
import math
import os
import shutil
import sys
import tempfile
import time


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


def _is_native_ext(name: str) -> bool:
    return name.endswith(".so") or name.endswith(".pyd")


def _so_module_match(filename: str, module_prefix: str) -> bool:
    """True for hot.so / hot.cpython-*.so, but not hot_native.*.so."""
    if not _is_native_ext(filename):
        return False
    return filename == module_prefix + ".so" or filename == module_prefix + ".pyd" \
        or filename.startswith(module_prefix + ".")


def _find_so(directory: str, module_prefix: str) -> str | None:
    candidates = [f for f in os.listdir(directory) if _so_module_match(f, module_prefix)]
    return os.path.join(directory, candidates[0]) if candidates else None


def _sandbox(work_dir: str, *, so_prefixes: list[str] | None) -> str:
    d = tempfile.mkdtemp(prefix="meridian_bench_")
    include_mypyc = so_prefixes is not None
    for name in os.listdir(work_dir):
        src = os.path.join(work_dir, name)
        if not os.path.isfile(src):
            continue
        if name.endswith(".py"):
            shutil.copy2(src, os.path.join(d, name))
            continue
        if so_prefixes is None or not _is_native_ext(name):
            continue
        if any(_so_module_match(name, p) for p in so_prefixes) or (
                include_mypyc and "__mypyc" in name):
            shutil.copy2(src, os.path.join(d, name))
    return d


def _bench(fn, args: tuple, iters: int) -> tuple[float, float]:
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


def main():
    if len(sys.argv) != 5:
        print(json.dumps({"error":
              "usage: generic_benchmark.py <work_dir> <native_module> "
              "<meridian_module> <cases_json>"}),
              file=sys.stderr)
        sys.exit(1)

    work_dir, native_mod, meridian_mod, cases_json = (
        sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4])
    work_dir = os.path.abspath(work_dir)

    try:
        cases = json.loads(cases_json)
    except json.JSONDecodeError as e:
        print(json.dumps({"error": f"invalid cases_json: {e}"}), file=sys.stderr)
        sys.exit(1)

    # L3 multi-module layout: work_dir/native/*.py and work_dir/meridian/*.{py,so}
    native_root = os.path.join(work_dir, "native")
    meridian_root = os.path.join(work_dir, "meridian")
    if not (os.path.isdir(native_root) and os.path.isdir(meridian_root)):
        native_root = work_dir
        meridian_root = work_dir

    native_py = os.path.join(native_root, native_mod + ".py")
    meridian_py = os.path.join(meridian_root, meridian_mod + ".py")
    for path, label in [(native_py, "native .py"), (meridian_py, "meridian .py")]:
        if not os.path.isfile(path):
            print(json.dumps({"error": f"{label} not found: {path}"}), file=sys.stderr)
            sys.exit(1)

    meridian_so = _find_so(meridian_root, meridian_mod)
    if meridian_so is None:
        print(json.dumps({"error":
              f"no .so for meridian module '{meridian_mod}' in {meridian_root}"}),
              file=sys.stderr)
        sys.exit(1)

    # Optional control: mypyc on naked source (same module name as native).
    bare_so = _find_so(meridian_root, native_mod) if native_root == meridian_root else None
    # If native_mod == meridian_mod, bare_so would collide — treat as no bare lane.
    if native_mod == meridian_mod:
        bare_so = None

    helper_prefixes = []
    for name in os.listdir(meridian_root):
        if not _is_native_ext(name):
            continue
        if name.startswith(native_mod + ".") or name.startswith(meridian_mod + ".") \
                or name == native_mod + ".so" or name == meridian_mod + ".so" \
                or name == native_mod + ".pyd" or name == meridian_mod + ".pyd":
            continue
        if name.startswith(native_mod) or name.startswith(meridian_mod):
            # still skip exact module prefixes via _so_module_match semantics later
            pass
        helper_prefixes.append(name.split(".", 1)[0])
    seen = set()
    helper_prefixes = [p for p in helper_prefixes
                       if p not in (native_mod, meridian_mod)
                       and not (p in seen or seen.add(p))]

    py_dir = _sandbox(native_root, so_prefixes=None)
    bare_dir = _sandbox(meridian_root, so_prefixes=[native_mod]) if bare_so else None
    ann_dir = _sandbox(meridian_root, so_prefixes=[meridian_mod] + helper_prefixes)

    failed = False
    try:
        sys.path.insert(0, py_dir)
        # Prefer a dedicated *_native.py if present; else native_mod.py (naked).
        native_py_name = native_mod + ".py"
        dedicated = os.path.join(py_dir, native_mod + "_native.py")
        if os.path.isfile(dedicated):
            # Not used as module name; keep loading native_mod.py which CompilePipeline writes naked.
            pass
        py_mod = _load_py(os.path.join(py_dir, native_py_name), native_mod + "__native")
        for name in list(sys.modules):
            if name == native_mod + "__native":
                continue
            mod = sys.modules[name]
            f = getattr(mod, "__file__", None)
            if f and os.path.realpath(f).startswith(os.path.realpath(py_dir) + os.sep):
                del sys.modules[name]
        sys.path.remove(py_dir)

        bare_so_mod = None
        if bare_dir is not None:
            sys.path.insert(0, bare_dir)
            bare_path = _find_so(bare_dir, native_mod)
            if bare_path:
                # Extension init is PyInit_<compile_name> — module name must match.
                bare_so_mod = _load_so(bare_path, native_mod)
            for name in list(sys.modules):
                if name == native_mod:
                    continue
                mod = sys.modules[name]
                f = getattr(mod, "__file__", None)
                if f and os.path.realpath(f).startswith(os.path.realpath(bare_dir) + os.sep):
                    del sys.modules[name]
            sys.path.remove(bare_dir)

        sys.path.insert(0, ann_dir)
        ann_so_mod = _load_so(_find_so(ann_dir, meridian_mod), meridian_mod)

        rows = []
        for entry in cases:
            fn_name, args_list, iters = entry[0], entry[1], entry[2]
            args = tuple(args_list)

            py_fn = getattr(py_mod, fn_name, None)
            ann_fn = getattr(ann_so_mod, fn_name, None)
            bare_fn = getattr(bare_so_mod, fn_name, None) if bare_so_mod else None

            if py_fn is None or ann_fn is None:
                missing = []
                if py_fn is None:
                    missing.append("native")
                if ann_fn is None:
                    missing.append("meridian")
                rows.append({
                    "func": f"{fn_name}({', '.join(str(a) for a in args)})",
                    "correct": False,
                    "error": f"function not found in: {missing}",
                })
                failed = True
                continue

            try:
                native_result = py_fn(*args)
                meridian_result = ann_fn(*args)
                if native_result != meridian_result:
                    rows.append({
                        "func": fn_name,
                        "correct": False,
                        "error": (
                            f"result mismatch: native={native_result}, "
                            f"meridian={meridian_result}"
                        ),
                    })
                    failed = True
                    continue
                if bare_fn is not None:
                    bare_result = bare_fn(*args)
                    if native_result != bare_result:
                        rows.append({
                            "func": fn_name,
                            "correct": False,
                            "error": (
                                f"result mismatch: native={native_result}, "
                                f"mypyc_bare={bare_result}"
                            ),
                        })
                        failed = True
                        continue
            except Exception as e:
                rows.append({
                    "func": fn_name,
                    "correct": False,
                    "error": f"execution error: {e}",
                })
                failed = True
                continue

            native_ns, cv_native = _bench(py_fn, args, iters)
            meridian_ns, cv_meridian = _bench(ann_fn, args, iters)
            bare_ns, cv_bare = (None, None)
            if bare_fn is not None:
                bare_ns, cv_bare = _bench(bare_fn, args, iters)

            row = {
                "func": f"{fn_name}({', '.join(str(a) for a in args)})",
                "correct": True,
                "native_ns": round(native_ns, 1),
                "meridian_ns": round(meridian_ns, 1),
                # Legacy aliases used by ConverterE2ETest
                "cpython_ns": round(native_ns, 1),
                "mypyc_gcp_ns": round(meridian_ns, 1),
                "speedup_vs_native": round(
                    native_ns / meridian_ns if meridian_ns > 0 else 0.0, 2),
                "speedup_gcp": round(
                    native_ns / meridian_ns if meridian_ns > 0 else 0.0, 2),
                "cv_native_pct": cv_native,
                "cv_meridian_pct": cv_meridian,
                "cv_cpython_pct": cv_native,
                "cv_gcp_pct": cv_meridian,
            }
            if bare_ns is not None:
                row["mypyc_bare_ns"] = round(bare_ns, 1)
                row["speedup_bare"] = round(
                    native_ns / bare_ns if bare_ns > 0 else 0.0, 2)
                row["cv_bare_pct"] = cv_bare
            rows.append(row)

        print(json.dumps({
            "ok": not failed,
            "baseline": "native_cpython",
            "compiled": "meridian_mypyc",
            "rows": rows,
        }))
    finally:
        for d in (py_dir, bare_dir, ann_dir):
            if d:
                shutil.rmtree(d, ignore_errors=True)

    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
