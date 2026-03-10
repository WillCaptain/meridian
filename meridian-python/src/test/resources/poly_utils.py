"""Polymorphic utility functions — used to test multi-type specialization.

These functions operate on both int and float inputs.
GCP sees them as generics at definition time.
The usage context (poly_utils_calls.py) supplies call sites for both types.
"""


def add(x, y):
    return x + y


def multiply(x, y):
    return x * y


def power(base, exp):
    result = 1
    for _ in range(exp):
        result = result * base
    return result
