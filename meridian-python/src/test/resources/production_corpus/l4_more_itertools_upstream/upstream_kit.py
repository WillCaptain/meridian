# L4 — upstream more-itertools recipe algorithm bodies (v10.7.0), MIT.
# take/nth keep upstream islice control flow; quantify/dotproduct use equivalent
# explicit loops (same math as sum(map(...))) so Meridian/mypyc stay sound.
# Source: https://github.com/more-itertools/more-itertools
from itertools import islice


def take(n, iterable):
    """Upstream recipes.take — first n items as a list."""
    return list(islice(iterable, n))


def nth(iterable, n):
    """Upstream recipes.nth — nth item."""
    return next(islice(iterable, n, n + 1))


def quantify(iterable):
    """Upstream recipes.quantify(pred=bool) — count truthy items."""
    count = 0
    for x in iterable:
        if x:
            count += 1
    return count


def dotproduct(vec1, vec2):
    """Upstream recipes.dotproduct — sum of pairwise products."""
    total = 0.0
    i = 0
    n = len(vec1)
    while i < n:
        total += vec1[i] * vec2[i]
        i += 1
    return total


def take_sum(n, iterable):
    """Consume take() into a scalar sum (bench-friendly)."""
    total = 0
    for x in take(n, iterable):
        total += x
    return total


def quantify_bool(iterable):
    return quantify(iterable)


def nth_item(iterable, n):
    return nth(iterable, n)


def dotproduct_seq(a, b):
    return dotproduct(a, b)
