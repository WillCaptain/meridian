# L6 hot path — upstream bodies Meridian will annotate (kept for mypyc).
from itertools import chain, compress, groupby, islice, repeat, zip_longest, tee, cycle, count
from operator import mul, lt, gt, itemgetter
from functools import partial
from collections import deque
from collections.abc import Sequence

_marker = object()
def take(n, iterable):
    """Return first *n* items of the *iterable* as a list.

        >>> take(3, range(10))
        [0, 1, 2]

    If there are fewer than *n* items in the iterable, all of them are
    returned.

        >>> take(10, range(3))
        [0, 1, 2]

    """
    return list(islice(iterable, n))

def tabulate(function, start=0):
    """Return an iterator over the results of ``func(start)``,
    ``func(start + 1)``, ``func(start + 2)``...

    *func* should be a function that accepts one integer argument.

    If *start* is not specified it defaults to 0. It will be incremented each
    time the iterator is advanced.

        >>> square = lambda x: x ** 2
        >>> iterator = tabulate(square, -3)
        >>> take(4, iterator)
        [9, 4, 1, 0]

    """
    return map(function, count(start))

def consume(iterator, n=None):
    """Advance *iterable* by *n* steps. If *n* is ``None``, consume it
    entirely.

    Efficiently exhausts an iterator without returning values. Defaults to
    consuming the whole iterator, but an optional second argument may be
    provided to limit consumption.

        >>> i = (x for x in range(10))
        >>> next(i)
        0
        >>> consume(i, 3)
        >>> next(i)
        4
        >>> consume(i)
        >>> next(i)
        Traceback (most recent call last):
          File "<stdin>", line 1, in <module>
        StopIteration

    If the iterator has fewer items remaining than the provided limit, the
    whole iterator will be consumed.

        >>> i = (x for x in range(3))
        >>> consume(i, 5)
        >>> next(i)
        Traceback (most recent call last):
          File "<stdin>", line 1, in <module>
        StopIteration

    """
    if n is None:
        deque(iterator, maxlen=0)
    else:
        next(islice(iterator, n, n), None)

def nth(iterable, n, default=None):
    """Returns the nth item or a default value.

    >>> l = range(10)
    >>> nth(l, 3)
    3
    >>> nth(l, 20, "zebra")
    'zebra'

    """
    return next(islice(iterable, n, None), default)

def all_equal(iterable, key=None):
    """
    Returns ``True`` if all the elements are equal to each other.

        >>> all_equal('aaaa')
        True
        >>> all_equal('aaab')
        False

    A function that accepts a single argument and returns a transformed version
    of each input item can be specified with *key*:

        >>> all_equal('AaaA', key=str.casefold)
        True
        >>> all_equal([1, 2, 3], key=lambda x: x < 10)
        True

    """
    iterator = groupby(iterable, key)
    for first in iterator:
        for second in iterator:
            return False
        return True
    return True

def quantify(iterable):
    """Upstream recipes.quantify(pred=bool) — explicit loop for typed mypyc."""
    count = 0
    for x in iterable:
        if x:
            count += 1
    return count

def pad_none(iterable):
    """Returns the sequence of elements and then returns ``None`` indefinitely.

        >>> take(5, pad_none(range(3)))
        [0, 1, 2, None, None]

    Useful for emulating the behavior of the built-in :func:`map` function.

    See also :func:`padded`.

    """
    return chain(iterable, repeat(None))

def ncycles(iterable, n):
    """Returns the sequence elements *n* times

    >>> list(ncycles(["a", "b"], 3))
    ['a', 'b', 'a', 'b', 'a', 'b']

    """
    return chain.from_iterable(repeat(tuple(iterable), n))

def dotproduct(vec1, vec2):
    """Upstream recipes.dotproduct — explicit loop for typed mypyc."""
    total = 0.0
    i = 0
    n = len(vec1)
    while i < n:
        total += vec1[i] * vec2[i]
        i += 1
    return total

def flatten(listOfLists):
    """Return an iterator flattening one level of nesting in a list of lists.

        >>> list(flatten([[0, 1], [2, 3]]))
        [0, 1, 2, 3]

    See also :func:`collapse`, which can flatten multiple levels of nesting.

    """
    return chain.from_iterable(listOfLists)

def powerset(iterable):
    """Yields all possible subsets of the iterable.

        >>> list(powerset([1, 2, 3]))
        [(), (1,), (2,), (3,), (1, 2), (1, 3), (2, 3), (1, 2, 3)]

    :func:`powerset` will operate on iterables that aren't :class:`set`
    instances, so repeated elements in the input will produce repeated elements
    in the output.

        >>> seq = [1, 1, 0]
        >>> list(powerset(seq))
        [(), (1,), (1,), (0,), (1, 1), (1, 0), (1, 0), (1, 1, 0)]

    For a variant that efficiently yields actual :class:`set` instances, see
    :func:`powerset_of_sets`.
    """
    s = list(iterable)
    return chain.from_iterable((combinations(s, r) for r in range(len(s) + 1)))

def unique_everseen(iterable, key=None):
    """
    Yield unique elements, preserving order.

        >>> list(unique_everseen('AAAABBBCCDAABBB'))
        ['A', 'B', 'C', 'D']
        >>> list(unique_everseen('ABBCcAD', str.lower))
        ['A', 'B', 'C', 'D']

    Sequences with a mix of hashable and unhashable items can be used.
    The function will be slower (i.e., `O(n^2)`) for unhashable items.

    Remember that ``list`` objects are unhashable - you can use the *key*
    parameter to transform the list to a tuple (which is hashable) to
    avoid a slowdown.

        >>> iterable = ([1, 2], [2, 3], [1, 2])
        >>> list(unique_everseen(iterable))  # Slow
        [[1, 2], [2, 3]]
        >>> list(unique_everseen(iterable, key=tuple))  # Faster
        [[1, 2], [2, 3]]

    Similarly, you may want to convert unhashable ``set`` objects with
    ``key=frozenset``. For ``dict`` objects,
    ``key=lambda x: frozenset(x.items())`` can be used.

    """
    seenset = set()
    seenset_add = seenset.add
    seenlist = []
    seenlist_add = seenlist.append
    use_key = key is not None
    for element in iterable:
        k = key(element) if use_key else element
        try:
            if k not in seenset:
                seenset_add(k)
                yield element
        except TypeError:
            if k not in seenlist:
                seenlist_add(k)
                yield element

def first_true(iterable, default=None, pred=None):
    """
    Returns the first true value in the iterable.

    If no true value is found, returns *default*

    If *pred* is not None, returns the first item for which
    ``pred(item) == True`` .

        >>> first_true(range(10))
        1
        >>> first_true(range(10), pred=lambda x: x > 5)
        6
        >>> first_true(range(10), default='missing', pred=lambda x: x > 9)
        'missing'

    """
    return next(filter(pred, iterable), default)

def prepend(value, iterator):
    """Yield *value*, followed by the elements in *iterator*.

        >>> value = '0'
        >>> iterator = ['1', '2', '3']
        >>> list(prepend(value, iterator))
        ['0', '1', '2', '3']

    To prepend multiple values, see :func:`itertools.chain`
    or :func:`value_chain`.

    """
    return chain([value], iterator)

def chunked(iterable, n, strict=False):
    """Break *iterable* into lists of length *n*:

        >>> list(chunked([1, 2, 3, 4, 5, 6], 3))
        [[1, 2, 3], [4, 5, 6]]

    By the default, the last yielded list will have fewer than *n* elements
    if the length of *iterable* is not divisible by *n*:

        >>> list(chunked([1, 2, 3, 4, 5, 6, 7, 8], 3))
        [[1, 2, 3], [4, 5, 6], [7, 8]]

    To use a fill-in value instead, see the :func:`grouper` recipe.

    If the length of *iterable* is not divisible by *n* and *strict* is
    ``True``, then ``ValueError`` will be raised before the last
    list is yielded.

    """
    iterator = iter(partial(take, n, iter(iterable)), [])
    if strict:
        if n is None:
            raise ValueError('n must not be None when using strict mode.')

        def ret():
            for chunk in iterator:
                if len(chunk) != n:
                    raise ValueError('iterable is not divisible by n.')
                yield chunk
        return iter(ret())
    else:
        return iterator

def first(iterable, default=_marker):
    """Return the first item of *iterable*, or *default* if *iterable* is
    empty.

        >>> first([0, 1, 2, 3])
        0
        >>> first([], 'some default')
        'some default'

    If *default* is not provided and there are no items in the iterable,
    raise ``ValueError``.

    :func:`first` is useful when you have a generator of expensive-to-retrieve
    values and want any arbitrary one. It is marginally shorter than
    ``next(iter(iterable), default)``.

    """
    for item in iterable:
        return item
    if default is _marker:
        raise ValueError('first() was called on an empty iterable, and no default value was provided.')
    return default

def last(iterable, default=_marker):
    """Return the last item of *iterable*, or *default* if *iterable* is
    empty.

        >>> last([0, 1, 2, 3])
        3
        >>> last([], 'some default')
        'some default'

    If *default* is not provided and there are no items in the iterable,
    raise ``ValueError``.
    """
    try:
        if isinstance(iterable, Sequence):
            return iterable[-1]
        if hasattr(iterable, '__reversed__'):
            return next(reversed(iterable))
        return deque(iterable, maxlen=1)[-1]
    except (IndexError, TypeError, StopIteration):
        if default is _marker:
            raise ValueError('last() was called on an empty iterable, and no default value was provided.')
        return default

def nth_or_last(iterable, n, default=_marker):
    """Return the nth or the last item of *iterable*,
    or *default* if *iterable* is empty.

        >>> nth_or_last([0, 1, 2, 3], 2)
        2
        >>> nth_or_last([0, 1], 2)
        1
        >>> nth_or_last([], 0, 'some default')
        'some default'

    If *default* is not provided and there are no items in the iterable,
    raise ``ValueError``.
    """
    return last(islice(iterable, n + 1), default=default)

def ilen(iterable):
    """Return the number of items in *iterable*.

    For example, there are 168 prime numbers below 1,000:

        >>> ilen(sieve(1000))
        168

    Equivalent to, but faster than::

        def ilen(iterable):
            count = 0
            for _ in iterable:
                count += 1
            return count

    This fully consumes the iterable, so handle with care.

    """
    return sum(compress(repeat(1), zip(iterable)))

def one(iterable, too_short=None, too_long=None):
    """Return the first item from *iterable*, which is expected to contain only
    that item. Raise an exception if *iterable* is empty or has more than one
    item.

    :func:`one` is useful for ensuring that an iterable contains only one item.
    For example, it can be used to retrieve the result of a database query
    that is expected to return a single row.

    If *iterable* is empty, ``ValueError`` will be raised. You may specify a
    different exception with the *too_short* keyword:

        >>> it = []
        >>> one(it)  # doctest: +IGNORE_EXCEPTION_DETAIL
        Traceback (most recent call last):
        ...
        ValueError: too few items in iterable (expected 1)'
        >>> too_short = IndexError('too few items')
        >>> one(it, too_short=too_short)  # doctest: +IGNORE_EXCEPTION_DETAIL
        Traceback (most recent call last):
        ...
        IndexError: too few items

    Similarly, if *iterable* contains more than one item, ``ValueError`` will
    be raised. You may specify a different exception with the *too_long*
    keyword:

        >>> it = ['too', 'many']
        >>> one(it)  # doctest: +IGNORE_EXCEPTION_DETAIL
        Traceback (most recent call last):
        ...
        ValueError: Expected exactly one item in iterable, but got 'too',
        'many', and perhaps more.
        >>> too_long = RuntimeError
        >>> one(it, too_long=too_long)  # doctest: +IGNORE_EXCEPTION_DETAIL
        Traceback (most recent call last):
        ...
        RuntimeError

    Note that :func:`one` attempts to advance *iterable* twice to ensure there
    is only one item. See :func:`spy` or :func:`peekable` to check iterable
    contents less destructively.

    """
    iterator = iter(iterable)
    for first in iterator:
        for second in iterator:
            msg = f'Expected exactly one item in iterable, but got {first!r}, {second!r}, and perhaps more.'
            raise too_long or ValueError(msg)
        return first
    raise too_short or ValueError('too few items in iterable (expected 1)')

def interleave(*iterables):
    """Return a new iterable yielding from each iterable in turn,
    until the shortest is exhausted.

        >>> list(interleave([1, 2, 3], [4, 5], [6, 7, 8]))
        [1, 4, 6, 2, 5, 7]

    For a version that doesn't terminate after the shortest iterable is
    exhausted, see :func:`interleave_longest`.

    """
    return chain.from_iterable(zip(*iterables))

def repeat_each(iterable, n=2):
    """Repeat each element in *iterable* *n* times.

    >>> list(repeat_each('ABC', 3))
    ['A', 'A', 'A', 'B', 'B', 'B', 'C', 'C', 'C']
    """
    return chain.from_iterable(map(repeat, iterable, repeat(n)))

def exactly_n(iterable, n, predicate=bool):
    """Return ``True`` if exactly ``n`` items in the iterable are ``True``
    according to the *predicate* function.

        >>> exactly_n([True, True, False], 2)
        True
        >>> exactly_n([True, True, False], 1)
        False
        >>> exactly_n([0, 1, 2, 3, 4, 5], 3, lambda x: x < 3)
        True

    The iterable will be advanced until ``n + 1`` truthy items are encountered,
    so avoid calling it on infinite iterables.

    """
    return ilen(islice(filter(predicate, iterable), n + 1)) == n

def map_if(iterable, pred, func, func_else=lambda x: x):
    """Evaluate each item from *iterable* using *pred*. If the result is
    equivalent to ``True``, transform the item with *func* and yield it.
    Otherwise, transform the item with *func_else* and yield it.

    *pred*, *func*, and *func_else* should each be functions that accept
    one argument. By default, *func_else* is the identity function.

    >>> from math import sqrt
    >>> iterable = list(range(-5, 5))
    >>> iterable
    [-5, -4, -3, -2, -1, 0, 1, 2, 3, 4]
    >>> list(map_if(iterable, lambda x: x > 3, lambda x: 'toobig'))
    [-5, -4, -3, -2, -1, 0, 1, 2, 3, 'toobig']
    >>> list(map_if(iterable, lambda x: x >= 0,
    ... lambda x: f'{sqrt(x):.2f}', lambda x: None))
    [None, None, None, None, None, '0.00', '1.00', '1.41', '1.73', '2.00']
    """
    for item in iterable:
        yield (func(item) if pred(item) else func_else(item))

def is_sorted(iterable, key=None, reverse=False, strict=False):
    """Returns ``True`` if the items of iterable are in sorted order, and
    ``False`` otherwise. *key* and *reverse* have the same meaning that they do
    in the built-in :func:`sorted` function.

    >>> is_sorted(['1', '2', '3', '4', '5'], key=int)
    True
    >>> is_sorted([5, 4, 3, 1, 2], reverse=True)
    False

    If *strict*, tests for strict sorting, that is, returns ``False`` if equal
    elements are found:

    >>> is_sorted([1, 2, 2])
    True
    >>> is_sorted([1, 2, 2], strict=True)
    False

    The function returns ``False`` after encountering the first out-of-order
    item, which means it may produce results that differ from the built-in
    :func:`sorted` function for objects with unusual comparison dynamics
    (like ``math.nan``). If there are no out-of-order items, the iterable is
    exhausted.
    """
    it = iterable if key is None else map(key, iterable)
    a, b = tee(it)
    next(b, None)
    if reverse:
        b, a = (a, b)
    return all(map(lt, a, b)) if strict else not any(map(lt, b, a))
