# L5 facade — absolute-import form of more_itertools hot surface.
from mi_recipes import (
    take, nth, all_equal, quantify, ncycles, dotproduct, flatten, first_true,
    consume, tabulate, pad_none, powerset, unique_everseen, prepend,
)
from mi_more import chunked, first, last, ilen, one

def take_list(n, iterable):
    return list(take(n, iterable))

def ncycles_list(iterable, n):
    return list(ncycles(iterable, n))

def flatten_list(xs):
    return list(flatten(xs))

def chunked_lens(iterable, n):
    return [len(c) for c in chunked(iterable, n)]

def quantify_bool(iterable):
    return quantify(iterable)

def nth_item(iterable, n):
    return nth(iterable, n)

def all_equal_seq(iterable):
    return all_equal(iterable)

def dotproduct_seq(a, b):
    return dotproduct(a, b)

def first_true_val(iterable):
    return first_true(iterable)

def ilen_seq(iterable):
    return ilen(iterable)
