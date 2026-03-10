"""Pure-math utilities — zero type annotations.

This is the baseline input for the GCP pipeline demo.
No 'n: int', no '-> int', nothing. Raw Python as a developer would write it.
"""


def factorial(n):
    if n <= 1:
        return 1
    return n * factorial(n - 1)


def fibonacci(n):
    a = 0
    b = 1
    for _ in range(n):
        a, b = b, a + b
    return a


def sum_squares(n):
    total = 0
    for i in range(n + 1):
        total = total + i * i
    return total


def is_prime(n):
    if n < 2:
        return False
    i = 2
    while i * i <= n:
        if n % i == 0:
            return False
        i = i + 1
    return True
