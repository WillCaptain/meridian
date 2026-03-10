"""Usage context for math_utils_bare.py.

GCP processes this file together with math_utils_bare.py.
The call sites here provide concrete argument types that let GCP
infer parameter types in math_utils_bare — without modifying the
original file.
"""
from math_utils_bare import factorial, fibonacci, sum_squares, is_prime

factorial(10)
factorial(20)
fibonacci(30)
sum_squares(100)
sum_squares(1000)
is_prime(997)
is_prime(9999991)
