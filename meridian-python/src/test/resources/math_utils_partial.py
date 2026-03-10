"""Pure-math utilities — parameter types only; NO return-type annotations.

This file is the starting point for the Meridian pipeline demo:
  1. Meridian parses this file and runs GCP type inference.
  2. GCP infers the return types from the function bodies.
  3. PythonAnnotationWriter rewrites this file adding '-> int' / '-> bool'.
  4. mypyc compiles the annotated version to a native C extension (.so).
  5. Python benchmarks both versions.
  GCP never executes any Python code — it only analyses the AST.
"""


def factorial(n: int):
    if n <= 1:
        return 1
    return n * factorial(n - 1)


def fibonacci(n: int):
    a = 0
    b = 1
    for _ in range(n):
        a, b = b, a + b
    return a


def sum_squares(n: int):
    total = 0
    for i in range(n + 1):
        total = total + i * i
    return total


def is_prime(n: int):
    if n < 2:
        return False
    i = 2
    while i * i <= n:
        if n % i == 0:
            return False
        i = i + 1
    return True
