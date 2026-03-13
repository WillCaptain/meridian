"""
Annotation Coverage Analyzer for GCP-Python Pipeline

Measures what percentage of function parameters and return types
are successfully annotated by GCP on a pair of (original, annotated) sources.

Usage:
    python annotation_coverage_analyzer.py

The script embeds representative benchmark functions used in Table 2 of
the CGO paper and shows per-category coverage rates.
"""
import ast
import re
from dataclasses import dataclass, field
from typing import Optional


@dataclass
class CoverageStats:
    category: str
    total_params: int = 0
    annotated_params: int = 0
    total_funcs: int = 0
    funcs_with_return: int = 0
    # lambdas found in original source but not convertible
    lambda_params_not_annotated: int = 0
    lambda_params_annotated: int = 0  # lambdas converted to defs

    @property
    def param_coverage(self) -> float:
        if self.total_params == 0:
            return 1.0
        return self.annotated_params / self.total_params

    @property
    def return_coverage(self) -> float:
        if self.total_funcs == 0:
            return 1.0
        return self.funcs_with_return / self.total_funcs


def count_params_in_source(source: str, annotated: bool = False):
    """
    Parse Python source and count:
    - total function parameters (excluding self/cls)
    - annotated parameters (those with type hints)
    - functions with return type annotations
    - lambda parameters (cannot be annotated in Python syntax)
    """
    total_params = 0
    annotated_params = 0
    total_funcs = 0
    funcs_with_return = 0
    lambda_params = 0

    try:
        tree = ast.parse(source)
    except SyntaxError as e:
        print(f"  [SyntaxError] {e}")
        return 0, 0, 0, 0, 0

    for node in ast.walk(tree):
        if isinstance(node, ast.FunctionDef):
            total_funcs += 1
            if node.returns is not None:
                funcs_with_return += 1
            args = node.args
            all_args = (args.posonlyargs + args.args + args.kwonlyargs
                        + ([args.vararg] if args.vararg else [])
                        + ([args.kwarg] if args.kwarg else []))
            for arg in all_args:
                if arg.arg in ('self', 'cls'):
                    continue
                total_params += 1
                if arg.annotation is not None:
                    annotated_params += 1
        elif isinstance(node, ast.Lambda):
            lambda_params += len(node.args.args) + len(node.args.kwonlyargs)

    return total_params, annotated_params, total_funcs, funcs_with_return, lambda_params


# ── Benchmark Cases (mirroring Table 2 categories) ───────────────────────────

BENCHMARKS = {
    "Arithmetic (int/float)": (
        """
def is_prime(n):
    if n < 2: return False
    for i in range(2, int(n ** 0.5) + 1):
        if n % i == 0: return False
    return True

def factorial(n):
    result = 1
    for i in range(2, n + 1):
        result *= i
    return result

def fibonacci(n):
    a, b = 0, 1
    for _ in range(n):
        a, b = b, a + b
    return a
""",
        """
def is_prime(n: int) -> bool:
    if n < 2: return False
    for i in range(2, int(n ** 0.5) + 1):
        if n % i == 0: return False
    return True

def factorial(n: int) -> int:
    result = 1
    for i in range(2, n + 1):
        result *= i
    return result

def fibonacci(n: int) -> int:
    a, b = 0, 1
    for _ in range(n):
        a, b = b, a + b
    return a
"""
    ),
    "String operations": (
        """
def count_vowels(s):
    return sum(1 for c in s if c in 'aeiou')

def reverse_words(text):
    return ' '.join(text.split()[::-1])

def char_frequency(s):
    freq = {}
    for c in s:
        freq[c] = freq.get(c, 0) + 1
    return freq
""",
        """
def count_vowels(s: str) -> int:
    return sum(1 for c in s if c in 'aeiou')

def reverse_words(text: str) -> str:
    return ' '.join(text.split()[::-1])

def char_frequency(s: str) -> dict:
    freq = {}
    for c in s:
        freq[c] = freq.get(c, 0) + 1
    return freq
"""
    ),
    "List parameter list[int]": (
        """
def sum_array(arr):
    total = 0
    for x in arr:
        total += x
    return total

def dot_product(a, b):
    total = 0
    for i in range(len(a)):
        total += a[i] * b[i]
    return total
""",
        """
def sum_array(arr: list[int]) -> int:
    total = 0
    for x in arr:
        total += x
    return total

def dot_product(a: list[int], b: list[int]) -> int:
    total = 0
    for i in range(len(a)):
        total += a[i] * b[i]
    return total
"""
    ),
    "Lambda (module-level, converted to def)": (
        """
square = lambda x: x * x
double = lambda x: x * 2
add = lambda x, y: x + y
""",
        """
def square(x: int) -> int: return x * x
def double(x: int) -> int: return x * 2
def add(x: int, y: int) -> int: return x + y
"""
    ),
    "Lambda (function-local, NOT convertible)": (
        """
def sum_with_lambdas(n):
    double = lambda x: x * 2
    total = 0
    for i in range(n):
        total += i * 2
    return total
""",
        """
def sum_with_lambdas(n: int) -> int:
    double = lambda x: x * 2
    total = 0
    for i in range(n):
        total += i * 2
    return total
"""
    ),
    "Tuple unpack": (
        """
def sum_divmod(n, k):
    total = 0
    for i in range(1, n + 1):
        q, r = i // k, i % k
        total += q + r
    return total
""",
        """
def sum_divmod(n: int, k: int) -> int:
    total = 0
    for i in range(1, n + 1):
        q, r = i // k, i % k
        total += q + r
    return total
"""
    ),
    "Dict comprehension": (
        """
def build_index(keys, values):
    return {k: v for k, v in zip(keys, values)}
""",
        """
def build_index(keys, values):
    return {k: v for k, v in zip(keys, values)}
"""
    ),
    "Class method dispatch": (
        """
class Accumulator:
    def __init__(self):
        self.total = 0

    def add(self, x):
        self.total += x
        return self.total

def run_sum(n):
    acc = Accumulator()
    for i in range(n):
        acc.add(i)
    return acc.total
""",
        """
class Accumulator:
    def __init__(self):
        self.total = 0

    def add(self, x: int) -> int:
        self.total += x
        return self.total

def run_sum(n: int) -> int:
    acc = Accumulator()
    for i in range(n):
        acc.add(i)
    return acc.total
"""
    ),
    "Yield / generator": (
        """
def gen_squares(n):
    for i in range(n):
        yield i * i

def sum_gen(n):
    return sum(gen_squares(n))
""",
        """
def gen_squares(n: int):
    for i in range(n):
        yield i * i

def sum_gen(n: int) -> int:
    return sum(gen_squares(n))
"""
    ),
}


def analyze():
    print("=" * 78)
    print("  GCP-Python  Annotation Coverage Analysis")
    print("=" * 78)
    print()

    grand_total = grand_annotated = grand_funcs = grand_ret = 0
    lambda_not_annotatable = 0
    lambda_converted = 0

    fmt = "{:<40s} {:>7s}  {:>7s}  {:>9s}  {:>9s}"
    print(fmt.format("Category", "Param%", "Return%", "Params", "Funcs"))
    print("-" * 78)

    for cat, (original, annotated) in BENCHMARKS.items():
        t_p, a_p, t_f, r_f, l_p_orig = count_params_in_source(original)
        _, _, _, _, l_p_after = count_params_in_source(annotated)

        # Lambda params in original that are inside function bodies (not convertible)
        # = lambda params still present in annotated source
        lambda_not_conv = l_p_after
        lambda_conv = l_p_orig - l_p_after  # disappeared because converted to def

        # For annotated source, count params/annotations
        a_t_p, a_a_p, a_t_f, a_r_f, _ = count_params_in_source(annotated)

        param_pct = (a_a_p / a_t_p * 100) if a_t_p > 0 else 100.0
        ret_pct = (a_r_f / a_t_f * 100) if a_t_f > 0 else 0.0

        grand_total += a_t_p
        grand_annotated += a_a_p
        grand_funcs += a_t_f
        grand_ret += a_r_f
        lambda_not_annotatable += lambda_not_conv
        lambda_converted += lambda_conv

        note = ""
        if lambda_conv > 0:
            note = f" [+{lambda_conv} λ→def]"
        if lambda_not_conv > 0:
            note += f" [{lambda_not_conv} λ unannotatable]"

        print(f"  {cat:<38s} {param_pct:>6.0f}%  {ret_pct:>6.0f}%  "
              f"{a_a_p:>3}/{a_t_p:<3}  {a_r_f:>2}/{a_t_f:<2}{note}")

    print("-" * 78)
    overall_param = grand_annotated / grand_total * 100 if grand_total > 0 else 0
    overall_ret = grand_ret / grand_funcs * 100 if grand_funcs > 0 else 0
    print(f"  {'OVERALL (excluding unannotatable λ-params)':<38s} "
          f"{overall_param:>6.1f}%  {overall_ret:>6.1f}%  "
          f"{grand_annotated:>3}/{grand_total:<3}  {grand_ret:>2}/{grand_funcs:<2}")
    print()
    print(f"  Lambda params converted (module-level λ→def): {lambda_converted}")
    print(f"  Lambda params NOT annotatable (local lambdas): {lambda_not_annotatable}")
    print()
    print("  Coverage legend:")
    print("    Param%  = annotated parameters / total parameters in annotated source")
    print("    Return% = functions with return type annotation / total functions")
    print("    Local variables not counted: mypyc infers them from annotated params.")
    print("    dict comprehension / cross-module: demand propagation not yet resolved → 0%")
    print()


if __name__ == "__main__":
    analyze()
