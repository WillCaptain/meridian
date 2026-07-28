# Recipes module — imports leaf helpers (L3 cross-module edge).
# Adapted from more-itertools recipes (MIT); annotations stripped.

from mi_numeric import tabulate_sum, mul_acc


def quantify(iterable, n):
    """Count truthy items among iterable[:n]."""
    count = 0
    i = 0
    while i < n:
        if iterable[i]:
            count += 1
        i += 1
    return count


def take_sum(n, iterable):
    """Sum of the first n items."""
    total = 0
    i = 0
    while i < n:
        total += iterable[i]
        i += 1
    return total


def all_equal(iterable, n):
    if n <= 1:
        return True
    first = iterable[0]
    i = 1
    while i < n:
        if iterable[i] != first:
            return False
        i += 1
    return True


def ncycles_sum(iterable, n, length):
    total = 0
    cycle = 0
    while cycle < n:
        i = 0
        while i < length:
            total += iterable[i]
            i += 1
        cycle += 1
    return total


def pairwise_product_sum(iterable, n):
    total = 0
    i = 0
    last = n - 1
    while i < last:
        total += iterable[i] * iterable[i + 1]
        i += 1
    return total


def first_true_index(iterable, n):
    i = 0
    while i < n:
        if iterable[i] != 0:
            return i
        i += 1
    return -1


def dotproduct(vec1, vec2, n):
    """Dot product via leaf mul_acc (cross-module call)."""
    return mul_acc(vec1, vec2, n)


def recipe_tabulate(n, start):
    """Facade over numeric.tabulate_sum (cross-module call)."""
    return tabulate_sum(n, start)
