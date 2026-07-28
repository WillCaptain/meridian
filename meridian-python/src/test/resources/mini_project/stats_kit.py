"""Mini analytics kit — naked source for Meridian compile samples.

Mirrors a small real module: hot loop, internal helper, dual-type tagger,
and dead code that usage never reaches.
"""


def _inc(x):
    """Internal helper; only reached from rolling_sum."""
    return x + 1


def rolling_sum(n):
    """Hot path: accumulate via helper (int-only from range)."""
    total = 0
    for i in range(n):
        total += _inc(i)
    return total


def tag(x):
    """Used at both int and str call sites in calls.py → specialize."""
    return x + x


def unused_histogram(n):
    """Dead for this sample — must be pruned from mypyc input."""
    bins = [0] * 10
    for i in range(n):
        bins[i % 10] += 1
    total = 0
    for b in bins:
        total += b
    return total


def unused_format_report(title, n):
    """Also unreachable from calls.py."""
    return title + ":" + str(n)
