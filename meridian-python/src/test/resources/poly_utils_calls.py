"""Usage context for poly_utils — contains BOTH int and float call sites.

GCP sees:
  add(1, 2)       → x: int,   y: int   → int specialization
  add(1.5, 2.5)   → x: float, y: float → float specialization
  multiply(3, 4)  → int
  multiply(1.5, 2.0) → float
  power(2, 10)    → int (only int call site)
"""
from poly_utils import add, multiply, power

# int call sites
add(1, 2)
multiply(3, 4)
power(2, 10)

# float call sites
add(1.5, 2.5)
multiply(1.5, 2.0)
