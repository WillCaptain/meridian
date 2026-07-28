# Leaf helpers — more-itertools-inspired (MIT recipe style).
# Annotations stripped. Imported by mi_recipes / mi_facade.


def tabulate_sum(n, start):
    """Sum of start..start+n-1 (tabulate-style, eager)."""
    total = 0
    i = 0
    v = start
    while i < n:
        total += v
        v += 1
        i += 1
    return total


def mul_acc(a, b, n):
    """Element-wise a[i]*b[i] accumulation for length n."""
    total = 0.0
    i = 0
    while i < n:
        total += a[i] * b[i]
        i += 1
    return total
