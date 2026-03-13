"""
numba_benchmark.py — warm-call Numba JIT baseline for Table 3 functions.

Protocol (mirrors generic_benchmark.py):
  • Each Numba function is called once to force JIT compilation (warm-up).
  • Then timed over N_ITER calls; median ns reported.
  • 5 independent chunks → median of medians, plus CV.

Output: JSON to stdout.
"""
import json
import time
import statistics
import math

from numba import njit

# ── Numba implementations ────────────────────────────────────────────────────

@njit
def factorial(n: int) -> int:
    acc = 1
    for i in range(2, n + 1):
        acc *= i
    return acc


@njit
def fibonacci(n: int) -> int:
    if n <= 1:
        return n
    a, b = 0, 1
    for _ in range(2, n + 1):
        a, b = b, a + b
    return b


@njit
def sum_squares(n: int) -> int:
    acc = 0
    for i in range(1, n + 1):
        acc += i * i
    return acc


@njit
def is_prime(n: int) -> bool:
    if n < 2:
        return False
    if n == 2:
        return True
    if n % 2 == 0:
        return False
    i = 3
    while i * i <= n:
        if n % i == 0:
            return False
        i += 2
    return True


# ── Timing helper ─────────────────────────────────────────────────────────────

N_CHUNKS = 7
DISCARD  = 3   # discard first 3 chunks (JIT + thermal ramp-up)

def bench(fn, arg, n_iter: int):
    """Return (median_ns, cv_pct) over N_CHUNKS kept chunks."""
    chunk_size = max(n_iter // N_CHUNKS, 1)
    kept = []
    for i in range(N_CHUNKS + DISCARD):
        t0 = time.perf_counter_ns()
        for _ in range(chunk_size):
            fn(arg)
        t1 = time.perf_counter_ns()
        if i >= DISCARD:
            kept.append((t1 - t0) / chunk_size)
    med = statistics.median(kept)
    cv  = (statistics.stdev(kept) / statistics.mean(kept) * 100) if len(kept) > 1 else 0.0
    return med, cv


# ── Cases ─────────────────────────────────────────────────────────────────────

CASES = [
    ("factorial",   factorial,   10,         1_000_000),
    ("factorial",   factorial,   20,         1_000_000),
    ("fibonacci",   fibonacci,   30,         1_000_000),
    ("sum_squares", sum_squares, 100,        1_000_000),
    ("sum_squares", sum_squares, 1000,         500_000),
    ("is_prime",    is_prime,    997,        1_000_000),
    ("is_prime",    is_prime,    9999991,      200_000),
]


# ── Pure-Python versions for CPython baseline ─────────────────────────────

def factorial_py(n: int) -> int:
    acc = 1
    for i in range(2, n + 1):
        acc *= i
    return acc

def fibonacci_py(n: int) -> int:
    if n <= 1:
        return n
    a, b = 0, 1
    for _ in range(2, n + 1):
        a, b = b, a + b
    return b

def sum_squares_py(n: int) -> int:
    acc = 0
    for i in range(1, n + 1):
        acc += i * i
    return acc

def is_prime_py(n: int) -> bool:
    if n < 2:
        return False
    if n == 2:
        return True
    if n % 2 == 0:
        return False
    i = 3
    while i * i <= n:
        if n % i == 0:
            return False
        i += 2
    return True

PY_FNS = {
    "factorial":   factorial_py,
    "fibonacci":   fibonacci_py,
    "sum_squares": sum_squares_py,
    "is_prime":    is_prime_py,
}


def main():
    results = []
    for name, fn, arg, n_iter in CASES:
        # ── warm-up: call many times to ensure LLVM fully compiles ────────
        for _ in range(200):
            fn(arg)

        numba_ns, numba_cv = bench(fn, arg, n_iter)
        cpython_ns, _      = bench(PY_FNS[name], arg, n_iter)
        numba_x = cpython_ns / numba_ns

        results.append({
            "function":     f"{name}({arg})",
            "cpython_ns":   round(cpython_ns, 1),
            "numba_ns":     round(numba_ns, 1),
            "numba_x":      round(numba_x, 2),
            "numba_cv_pct": round(numba_cv, 1),
        })
        print(
            f"  {name}({arg}):  CPython {cpython_ns:.1f} ns  "
            f"Numba {numba_ns:.1f} ns  ({numba_x:.2f}×)  CV={numba_cv:.1f}%",
            flush=True,
        )

    print("\n--- JSON ---")
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
