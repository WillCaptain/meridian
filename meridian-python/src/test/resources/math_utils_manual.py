"""Pure-math utilities — MANUALLY annotated (oracle ceiling for Table 1).

This is the developer-annotated version of math_utils_bare.py.
A skilled Python developer would write exactly these annotations.
Used as the oracle baseline: mypyc(manual) represents the performance
ceiling achievable with full human annotation effort.
"""


def factorial(n: int) -> int:
    if n <= 1:
        return 1
    return n * factorial(n - 1)


def fibonacci(n: int) -> int:
    a: int = 0
    b: int = 1
    for _ in range(n):
        a, b = b, a + b
    return a


def sum_squares(n: int) -> int:
    total: int = 0
    for i in range(n + 1):
        total = total + i * i
    return total


def is_prime(n: int) -> bool:
    if n < 2:
        return False
    i: int = 2
    while i * i <= n:
        if n % i == 0:
            return False
        i = i + 1
    return True
