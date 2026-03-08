"""Pure-math utility functions — a good mypyc target."""


def factorial(n: int) -> int:
    if n <= 1:
        return 1
    return n * factorial(n - 1)


def fibonacci(n: int) -> int:
    a = 0
    b = 1
    for _ in range(n):
        a, b = b, a + b
    return a


def sum_squares(n: int) -> int:
    total = 0
    for i in range(n + 1):
        total = total + i * i
    return total


def is_prime(n: int) -> bool:
    if n < 2:
        return False
    i = 2
    while i * i <= n:
        if n % i == 0:
            return False
        i = i + 1
    return True
