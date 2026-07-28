# L2 corpus — adapted from itertools / more-itertools recipe style (MIT).
# All PEP-484 annotations stripped; Meridian must recover types from calls.py.
# List-based hot paths (not lazy iterators) so mypyc can specialize.

def quantify(iterable, n):
    """Count truthy items among iterable[:n] (recipe-style quantify)."""
    count = 0
    i = 0
    while i < n:
        if iterable[i]:
            count += 1
        i += 1
    return count


def nth(iterable, n):
    """Return the nth item of a sequence (0-based)."""
    return iterable[n]


def take_sum(n, iterable):
    """Sum of the first n items."""
    total = 0
    i = 0
    while i < n:
        total += iterable[i]
        i += 1
    return total


def all_equal(iterable, n):
    """True if the first n items are all equal."""
    if n <= 1:
        return True
    first = iterable[0]
    i = 1
    while i < n:
        if iterable[i] != first:
            return False
        i += 1
    return True


def dotproduct(vec1, vec2, n):
    """Sum of element-wise products for length-n vectors."""
    total = 0.0
    i = 0
    while i < n:
        total += vec1[i] * vec2[i]
        i += 1
    return total


def ncycles_sum(iterable, n, length):
    """Sum of iterable (length items) repeated n times."""
    total = 0
    cycle = 0
    while cycle < n:
        i = 0
        while i < length:
            total += iterable[i]
            i += 1
        cycle += 1
    return total


def first_true_index(iterable, n):
    """Index of first non-zero item in iterable[:n], or -1."""
    i = 0
    while i < n:
        if iterable[i] != 0:
            return i
        i += 1
    return -1


def pairwise_product_sum(iterable, n):
    """Sum of iterable[i] * iterable[i+1] for i in 0..n-2."""
    total = 0
    i = 0
    last = n - 1
    while i < last:
        total += iterable[i] * iterable[i + 1]
        i += 1
    return total


def consume_count(iterable, n):
    """Advance through n items; return how many were visited."""
    count = 0
    i = 0
    while i < n:
        _ = iterable[i]
        count += 1
        i += 1
    return count


def tabulate_sum(n, start):
    """Sum of start, start+1, … for n terms (tabulate-style)."""
    total = 0
    i = 0
    v = start
    while i < n:
        total += v
        v += 1
        i += 1
    return total


# Leave-gap honesty probe: HOF often stays unannotated rather than invent types.
def apply_twice(fn, x):
    return fn(fn(x))
