# L6 facade — annotated hot kernels over mi_hot.
from mi_hot import (
    take, nth, all_equal, quantify, ncycles, dotproduct, flatten, first_true,
    chunked, ilen,
)

def take_list(n, iterable):
    return list(take(n, iterable))

def take_sum(n, iterable):
    total = 0
    for x in take(n, iterable):
        total += x
    return total

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
