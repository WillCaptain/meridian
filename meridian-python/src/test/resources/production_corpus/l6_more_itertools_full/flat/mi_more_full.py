# L6 — more.py function surface (v10.7.0); empty-tuple/complex funcs dropped for GCP convert.
from collections import Counter, defaultdict, deque, abc
from collections.abc import Sequence
from contextlib import suppress
from functools import partial, reduce, wraps, cached_property
from heapq import heapify, heapreplace
from itertools import (
    chain, combinations, compress, count, cycle, dropwhile, groupby, islice,
    repeat, starmap, takewhile, tee, zip_longest, product,
)
from math import comb, e, exp, factorial, floor, fsum, log, log1p, perm, tau
from operator import itemgetter, mul, sub, gt, lt
from random import random, randrange, shuffle, uniform
from sys import hexversion, maxsize
from time import monotonic
from queue import Empty, Queue
import math
import warnings
from mi_recipes import (
    take, nth, powerset, sieve, unique_everseen, all_equal, flatten, consume,
)
_marker = object()
class UnequalIterablesError(ValueError):
    pass
def _zip_equal(*iterables):
    return zip(*iterables)

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

def consumer(func):
    """Decorator that automatically advances a PEP-342-style "reverse iterator"
    to its first yield point so you don't have to call ``next()`` on it
    manually.

        >>> @consumer
        ... def tally():
        ...     i = 0
        ...     while True:
        ...         print('Thing number %s is %s.' % (i, (yield)))
        ...         i += 1
        ...
        >>> t = tally()
        >>> t.send('red')
        Thing number 0 is red.
        >>> t.send('fish')
        Thing number 1 is fish.

    Without the decorator, you would have to call ``next(t)`` before
    ``t.send()`` could be used.

    """

    @wraps(func)
    def wrapper(*args, **kwargs):
        gen = func(*args, **kwargs)
        next(gen)
        return gen
    return wrapper

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

def iterate(func, start):
    """Return ``start``, ``func(start)``, ``func(func(start))``, ...

    Produces an infinite iterator. To add a stopping condition,
    use :func:`take`, ``takewhile``, or :func:`takewhile_inclusive`:.

    >>> take(10, iterate(lambda x: 2*x, 1))
    [1, 2, 4, 8, 16, 32, 64, 128, 256, 512]

    >>> collatz = lambda x: 3*x + 1 if x%2==1 else x // 2
    >>> list(takewhile_inclusive(lambda x: x!=1, iterate(collatz, 10)))
    [10, 5, 16, 8, 4, 2, 1]

    """
    with suppress(StopIteration):
        while True:
            yield start
            start = func(start)

def with_iter(context_manager):
    """Wrap an iterable in a ``with`` statement, so it closes once exhausted.

    For example, this will close the file when the iterator is exhausted::

        upper_lines = (line.upper() for line in with_iter(open('foo')))

    Any context manager which returns an iterable is a candidate for
    ``with_iter``.

    """
    with context_manager as iterable:
        yield from iterable

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

def raise_(exception, *args):
    raise exception(*args)

def strictly_n(iterable, n, too_short=None, too_long=None):
    """Validate that *iterable* has exactly *n* items and return them if
    it does. If it has fewer than *n* items, call function *too_short*
    with those items. If it has more than *n* items, call function
    *too_long* with the first ``n + 1`` items.

        >>> iterable = ['a', 'b', 'c', 'd']
        >>> n = 4
        >>> list(strictly_n(iterable, n))
        ['a', 'b', 'c', 'd']

    Note that the returned iterable must be consumed in order for the check to
    be made.

    By default, *too_short* and *too_long* are functions that raise
    ``ValueError``.

        >>> list(strictly_n('ab', 3))  # doctest: +IGNORE_EXCEPTION_DETAIL
        Traceback (most recent call last):
        ...
        ValueError: too few items in iterable (got 2)

        >>> list(strictly_n('abc', 2))  # doctest: +IGNORE_EXCEPTION_DETAIL
        Traceback (most recent call last):
        ...
        ValueError: too many items in iterable (got at least 3)

    You can instead supply functions that do something else.
    *too_short* will be called with the number of items in *iterable*.
    *too_long* will be called with `n + 1`.

        >>> def too_short(item_count):
        ...     raise RuntimeError
        >>> it = strictly_n('abcd', 6, too_short=too_short)
        >>> list(it)  # doctest: +IGNORE_EXCEPTION_DETAIL
        Traceback (most recent call last):
        ...
        RuntimeError

        >>> def too_long(item_count):
        ...     print('The boss is going to hear about this')
        >>> it = strictly_n('abcdef', 4, too_long=too_long)
        >>> list(it)
        The boss is going to hear about this
        ['a', 'b', 'c', 'd']

    """
    if too_short is None:
        too_short = lambda item_count: raise_(ValueError, f'Too few items in iterable (got {item_count})')
    if too_long is None:
        too_long = lambda item_count: raise_(ValueError, f'Too many items in iterable (got at least {item_count})')
    it = iter(iterable)
    sent = 0
    for item in islice(it, n):
        yield item
        sent += 1
    if sent < n:
        too_short(sent)
        return
    for item in it:
        too_long(n + 1)
        return

def intersperse(e, iterable, n=1):
    """Intersperse filler element *e* among the items in *iterable*, leaving
    *n* items between each filler element.

        >>> list(intersperse('!', [1, 2, 3, 4, 5]))
        [1, '!', 2, '!', 3, '!', 4, '!', 5]

        >>> list(intersperse(None, [1, 2, 3, 4, 5], n=2))
        [1, 2, None, 3, 4, None, 5]

    """
    if n == 0:
        raise ValueError('n must be > 0')
    elif n == 1:
        return islice(interleave(repeat(e), iterable), 1, None)
    else:
        filler = repeat([e])
        chunks = chunked(iterable, n)
        return flatten(islice(interleave(filler, chunks), 1, None))

def unique_to_each(*iterables):
    """Return the elements from each of the input iterables that aren't in the
    other input iterables.

    For example, suppose you have a set of packages, each with a set of
    dependencies::

        {'pkg_1': {'A', 'B'}, 'pkg_2': {'B', 'C'}, 'pkg_3': {'B', 'D'}}

    If you remove one package, which dependencies can also be removed?

    If ``pkg_1`` is removed, then ``A`` is no longer necessary - it is not
    associated with ``pkg_2`` or ``pkg_3``. Similarly, ``C`` is only needed for
    ``pkg_2``, and ``D`` is only needed for ``pkg_3``::

        >>> unique_to_each({'A', 'B'}, {'B', 'C'}, {'B', 'D'})
        [['A'], ['C'], ['D']]

    If there are duplicates in one input iterable that aren't in the others
    they will be duplicated in the output. Input order is preserved::

        >>> unique_to_each("mississippi", "missouri")
        [['p', 'p'], ['o', 'u', 'r']]

    It is assumed that the elements of each iterable are hashable.

    """
    pool = [list(it) for it in iterables]
    counts = Counter(chain.from_iterable(map(set, pool)))
    uniques = {element for element in counts if counts[element] == 1}
    return [list(filter(uniques.__contains__, it)) for it in pool]

def substrings(iterable):
    """Yield all of the substrings of *iterable*.

        >>> [''.join(s) for s in substrings('more')]
        ['m', 'o', 'r', 'e', 'mo', 'or', 're', 'mor', 'ore', 'more']

    Note that non-string iterables can also be subdivided.

        >>> list(substrings([0, 1, 2]))
        [(0,), (1,), (2,), (0, 1), (1, 2), (0, 1, 2)]

    """
    seq = []
    for item in iter(iterable):
        seq.append(item)
        yield (item,)
    seq = tuple(seq)
    item_count = len(seq)
    for n in range(2, item_count + 1):
        for i in range(item_count - n + 1):
            yield seq[i:i + n]

def substrings_indexes(seq, reverse=False):
    """Yield all substrings and their positions in *seq*

    The items yielded will be a tuple of the form ``(substr, i, j)``, where
    ``substr == seq[i:j]``.

    This function only works for iterables that support slicing, such as
    ``str`` objects.

    >>> for item in substrings_indexes('more'):
    ...    print(item)
    ('m', 0, 1)
    ('o', 1, 2)
    ('r', 2, 3)
    ('e', 3, 4)
    ('mo', 0, 2)
    ('or', 1, 3)
    ('re', 2, 4)
    ('mor', 0, 3)
    ('ore', 1, 4)
    ('more', 0, 4)

    Set *reverse* to ``True`` to yield the same items in the opposite order.


    """
    r = range(1, len(seq) + 1)
    if reverse:
        r = reversed(r)
    return ((seq[i:i + L], i, i + L) for L in r for i in range(len(seq) - L + 1))

def spy(iterable, n=1):
    """Return a 2-tuple with a list containing the first *n* elements of
    *iterable*, and an iterator with the same items as *iterable*.
    This allows you to "look ahead" at the items in the iterable without
    advancing it.

    There is one item in the list by default:

        >>> iterable = 'abcdefg'
        >>> head, iterable = spy(iterable)
        >>> head
        ['a']
        >>> list(iterable)
        ['a', 'b', 'c', 'd', 'e', 'f', 'g']

    You may use unpacking to retrieve items instead of lists:

        >>> (head,), iterable = spy('abcdefg')
        >>> head
        'a'
        >>> (first, second), iterable = spy('abcdefg', 2)
        >>> first
        'a'
        >>> second
        'b'

    The number of items requested can be larger than the number of items in
    the iterable:

        >>> iterable = [1, 2, 3, 4, 5]
        >>> head, iterable = spy(iterable, 10)
        >>> head
        [1, 2, 3, 4, 5]
        >>> list(iterable)
        [1, 2, 3, 4, 5]

    """
    p, q = tee(iterable)
    return (take(n, q), p)

def interleave(*iterables):
    """Return a new iterable yielding from each iterable in turn,
    until the shortest is exhausted.

        >>> list(interleave([1, 2, 3], [4, 5], [6, 7, 8]))
        [1, 4, 6, 2, 5, 7]

    For a version that doesn't terminate after the shortest iterable is
    exhausted, see :func:`interleave_longest`.

    """
    return chain.from_iterable(zip(*iterables))

def interleave_longest(*iterables):
    """Return a new iterable yielding from each iterable in turn,
    skipping any that are exhausted.

        >>> list(interleave_longest([1, 2, 3], [4, 5], [6, 7, 8]))
        [1, 4, 6, 2, 5, 7, 3, 8]

    This function produces the same output as :func:`roundrobin`, but may
    perform better for some inputs (in particular when the number of iterables
    is large).

    """
    i = chain.from_iterable(zip_longest(*iterables, fillvalue=_marker))
    return (x for x in i if x is not _marker)

def interleave_evenly(iterables, lengths=None):
    """
    Interleave multiple iterables so that their elements are evenly distributed
    throughout the output sequence.

    >>> iterables = [1, 2, 3, 4, 5], ['a', 'b']
    >>> list(interleave_evenly(iterables))
    [1, 2, 'a', 3, 4, 'b', 5]

    >>> iterables = [[1, 2, 3], [4, 5], [6, 7, 8]]
    >>> list(interleave_evenly(iterables))
    [1, 6, 4, 2, 7, 3, 8, 5]

    This function requires iterables of known length. Iterables without
    ``__len__()`` can be used by manually specifying lengths with *lengths*:

    >>> from itertools import combinations, repeat
    >>> iterables = [combinations(range(4), 2), ['a', 'b', 'c']]
    >>> lengths = [4 * (4 - 1) // 2, 3]
    >>> list(interleave_evenly(iterables, lengths=lengths))
    [(0, 1), (0, 2), 'a', (0, 3), (1, 2), 'b', (1, 3), (2, 3), 'c']

    Based on Bresenham's algorithm.
    """
    if lengths is None:
        try:
            lengths = [len(it) for it in iterables]
        except TypeError:
            raise ValueError('Iterable lengths could not be determined automatically. Specify them with the lengths keyword.')
    elif len(iterables) != len(lengths):
        raise ValueError('Mismatching number of iterables and lengths.')
    dims = len(lengths)
    lengths_permute = sorted(range(dims), key=lambda i: lengths[i], reverse=True)
    lengths_desc = [lengths[i] for i in lengths_permute]
    iters_desc = [iter(iterables[i]) for i in lengths_permute]
    delta_primary, deltas_secondary = (lengths_desc[0], lengths_desc[1:])
    iter_primary, iters_secondary = (iters_desc[0], iters_desc[1:])
    errors = [delta_primary // dims] * len(deltas_secondary)
    to_yield = sum(lengths)
    while to_yield:
        yield next(iter_primary)
        to_yield -= 1
        errors = [e - delta for e, delta in zip(errors, deltas_secondary)]
        for i, e_ in enumerate(errors):
            if e_ < 0:
                yield next(iters_secondary[i])
                to_yield -= 1
                errors[i] += delta_primary

def collapse(iterable, base_type=None, levels=None):
    """Flatten an iterable with multiple levels of nesting (e.g., a list of
    lists of tuples) into non-iterable types.

        >>> iterable = [(1, 2), ([3, 4], [[5], [6]])]
        >>> list(collapse(iterable))
        [1, 2, 3, 4, 5, 6]

    Binary and text strings are not considered iterable and
    will not be collapsed.

    To avoid collapsing other types, specify *base_type*:

        >>> iterable = ['ab', ('cd', 'ef'), ['gh', 'ij']]
        >>> list(collapse(iterable, base_type=tuple))
        ['ab', ('cd', 'ef'), 'gh', 'ij']

    Specify *levels* to stop flattening after a certain level:

    >>> iterable = [('a', ['b']), ('c', ['d'])]
    >>> list(collapse(iterable))  # Fully flattened
    ['a', 'b', 'c', 'd']
    >>> list(collapse(iterable, levels=1))  # Only one level flattened
    ['a', ['b'], 'c', ['d']]

    """
    stack = deque()
    stack.appendleft((0, repeat(iterable, 1)))
    while stack:
        node_group = stack.popleft()
        level, nodes = node_group
        if levels is not None and level > levels:
            yield from nodes
            continue
        for node in nodes:
            if isinstance(node, (str, bytes)) or (base_type is not None and isinstance(node, base_type)):
                yield node
            else:
                try:
                    tree = iter(node)
                except TypeError:
                    yield node
                else:
                    stack.appendleft(node_group)
                    stack.appendleft((level + 1, tree))
                    break

def side_effect(func, iterable, chunk_size=None, before=None, after=None):
    """Invoke *func* on each item in *iterable* (or on each *chunk_size* group
    of items) before yielding the item.

    `func` must be a function that takes a single argument. Its return value
    will be discarded.

    *before* and *after* are optional functions that take no arguments. They
    will be executed before iteration starts and after it ends, respectively.

    `side_effect` can be used for logging, updating progress bars, or anything
    that is not functionally "pure."

    Emitting a status message:

        >>> from more_itertools import consume
        >>> func = lambda item: print('Received {}'.format(item))
        >>> consume(side_effect(func, range(2)))
        Received 0
        Received 1

    Operating on chunks of items:

        >>> pair_sums = []
        >>> func = lambda chunk: pair_sums.append(sum(chunk))
        >>> list(side_effect(func, [0, 1, 2, 3, 4, 5], 2))
        [0, 1, 2, 3, 4, 5]
        >>> list(pair_sums)
        [1, 5, 9]

    Writing to a file-like object:

        >>> from io import StringIO
        >>> from more_itertools import consume
        >>> f = StringIO()
        >>> func = lambda x: print(x, file=f)
        >>> before = lambda: print(u'HEADER', file=f)
        >>> after = f.close
        >>> it = [u'a', u'b', u'c']
        >>> consume(side_effect(func, it, before=before, after=after))
        >>> f.closed
        True

    """
    try:
        if before is not None:
            before()
        if chunk_size is None:
            for item in iterable:
                func(item)
                yield item
        else:
            for chunk in chunked(iterable, chunk_size):
                func(chunk)
                yield from chunk
    finally:
        if after is not None:
            after()

def sliced(seq, n, strict=False):
    """Yield slices of length *n* from the sequence *seq*.

    >>> list(sliced((1, 2, 3, 4, 5, 6), 3))
    [(1, 2, 3), (4, 5, 6)]

    By the default, the last yielded slice will have fewer than *n* elements
    if the length of *seq* is not divisible by *n*:

    >>> list(sliced((1, 2, 3, 4, 5, 6, 7, 8), 3))
    [(1, 2, 3), (4, 5, 6), (7, 8)]

    If the length of *seq* is not divisible by *n* and *strict* is
    ``True``, then ``ValueError`` will be raised before the last
    slice is yielded.

    This function will only work for iterables that support slicing.
    For non-sliceable iterables, see :func:`chunked`.

    """
    iterator = takewhile(len, (seq[i:i + n] for i in count(0, n)))
    if strict:

        def ret():
            for _slice in iterator:
                if len(_slice) != n:
                    raise ValueError('seq is not divisible by n.')
                yield _slice
        return iter(ret())
    else:
        return iterator

def split_at(iterable, pred, maxsplit=-1, keep_separator=False):
    """Yield lists of items from *iterable*, where each list is delimited by
    an item where callable *pred* returns ``True``.

        >>> list(split_at('abcdcba', lambda x: x == 'b'))
        [['a'], ['c', 'd', 'c'], ['a']]

        >>> list(split_at(range(10), lambda n: n % 2 == 1))
        [[0], [2], [4], [6], [8], []]

    At most *maxsplit* splits are done. If *maxsplit* is not specified or -1,
    then there is no limit on the number of splits:

        >>> list(split_at(range(10), lambda n: n % 2 == 1, maxsplit=2))
        [[0], [2], [4, 5, 6, 7, 8, 9]]

    By default, the delimiting items are not included in the output.
    To include them, set *keep_separator* to ``True``.

        >>> list(split_at('abcdcba', lambda x: x == 'b', keep_separator=True))
        [['a'], ['b'], ['c', 'd', 'c'], ['b'], ['a']]

    """
    if maxsplit == 0:
        yield list(iterable)
        return
    buf = []
    it = iter(iterable)
    for item in it:
        if pred(item):
            yield buf
            if keep_separator:
                yield [item]
            if maxsplit == 1:
                yield list(it)
                return
            buf = []
            maxsplit -= 1
        else:
            buf.append(item)
    yield buf

def split_before(iterable, pred, maxsplit=-1):
    """Yield lists of items from *iterable*, where each list ends just before
    an item for which callable *pred* returns ``True``:

        >>> list(split_before('OneTwo', lambda s: s.isupper()))
        [['O', 'n', 'e'], ['T', 'w', 'o']]

        >>> list(split_before(range(10), lambda n: n % 3 == 0))
        [[0, 1, 2], [3, 4, 5], [6, 7, 8], [9]]

    At most *maxsplit* splits are done. If *maxsplit* is not specified or -1,
    then there is no limit on the number of splits:

        >>> list(split_before(range(10), lambda n: n % 3 == 0, maxsplit=2))
        [[0, 1, 2], [3, 4, 5], [6, 7, 8, 9]]
    """
    if maxsplit == 0:
        yield list(iterable)
        return
    buf = []
    it = iter(iterable)
    for item in it:
        if pred(item) and buf:
            yield buf
            if maxsplit == 1:
                yield ([item] + list(it))
                return
            buf = []
            maxsplit -= 1
        buf.append(item)
    if buf:
        yield buf

def split_after(iterable, pred, maxsplit=-1):
    """Yield lists of items from *iterable*, where each list ends with an
    item where callable *pred* returns ``True``:

        >>> list(split_after('one1two2', lambda s: s.isdigit()))
        [['o', 'n', 'e', '1'], ['t', 'w', 'o', '2']]

        >>> list(split_after(range(10), lambda n: n % 3 == 0))
        [[0], [1, 2, 3], [4, 5, 6], [7, 8, 9]]

    At most *maxsplit* splits are done. If *maxsplit* is not specified or -1,
    then there is no limit on the number of splits:

        >>> list(split_after(range(10), lambda n: n % 3 == 0, maxsplit=2))
        [[0], [1, 2, 3], [4, 5, 6, 7, 8, 9]]

    """
    if maxsplit == 0:
        yield list(iterable)
        return
    buf = []
    it = iter(iterable)
    for item in it:
        buf.append(item)
        if pred(item) and buf:
            yield buf
            if maxsplit == 1:
                buf = list(it)
                if buf:
                    yield buf
                return
            buf = []
            maxsplit -= 1
    if buf:
        yield buf

def split_when(iterable, pred, maxsplit=-1):
    """Split *iterable* into pieces based on the output of *pred*.
    *pred* should be a function that takes successive pairs of items and
    returns ``True`` if the iterable should be split in between them.

    For example, to find runs of increasing numbers, split the iterable when
    element ``i`` is larger than element ``i + 1``:

        >>> list(split_when([1, 2, 3, 3, 2, 5, 2, 4, 2], lambda x, y: x > y))
        [[1, 2, 3, 3], [2, 5], [2, 4], [2]]

    At most *maxsplit* splits are done. If *maxsplit* is not specified or -1,
    then there is no limit on the number of splits:

        >>> list(split_when([1, 2, 3, 3, 2, 5, 2, 4, 2],
        ...                 lambda x, y: x > y, maxsplit=2))
        [[1, 2, 3, 3], [2, 5], [2, 4, 2]]

    """
    if maxsplit == 0:
        yield list(iterable)
        return
    it = iter(iterable)
    try:
        cur_item = next(it)
    except StopIteration:
        return
    buf = [cur_item]
    for next_item in it:
        if pred(cur_item, next_item):
            yield buf
            if maxsplit == 1:
                yield ([next_item] + list(it))
                return
            buf = []
            maxsplit -= 1
        buf.append(next_item)
        cur_item = next_item
    yield buf

def split_into(iterable, sizes):
    """Yield a list of sequential items from *iterable* of length 'n' for each
    integer 'n' in *sizes*.

        >>> list(split_into([1,2,3,4,5,6], [1,2,3]))
        [[1], [2, 3], [4, 5, 6]]

    If the sum of *sizes* is smaller than the length of *iterable*, then the
    remaining items of *iterable* will not be returned.

        >>> list(split_into([1,2,3,4,5,6], [2,3]))
        [[1, 2], [3, 4, 5]]

    If the sum of *sizes* is larger than the length of *iterable*, fewer items
    will be returned in the iteration that overruns *iterable* and further
    lists will be empty:

        >>> list(split_into([1,2,3,4], [1,2,3,4]))
        [[1], [2, 3], [4], []]

    When a ``None`` object is encountered in *sizes*, the returned list will
    contain items up to the end of *iterable* the same way that
    :func:`itertools.slice` does:

        >>> list(split_into([1,2,3,4,5,6,7,8,9,0], [2,3,None]))
        [[1, 2], [3, 4, 5], [6, 7, 8, 9, 0]]

    :func:`split_into` can be useful for grouping a series of items where the
    sizes of the groups are not uniform. An example would be where in a row
    from a table, multiple columns represent elements of the same feature
    (e.g. a point represented by x,y,z) but, the format is not the same for
    all columns.
    """
    it = iter(iterable)
    for size in sizes:
        if size is None:
            yield list(it)
            return
        else:
            yield list(islice(it, size))

def padded(iterable, fillvalue=None, n=None, next_multiple=False):
    """Yield the elements from *iterable*, followed by *fillvalue*, such that
    at least *n* items are emitted.

        >>> list(padded([1, 2, 3], '?', 5))
        [1, 2, 3, '?', '?']

    If *next_multiple* is ``True``, *fillvalue* will be emitted until the
    number of items emitted is a multiple of *n*:

        >>> list(padded([1, 2, 3, 4], n=3, next_multiple=True))
        [1, 2, 3, 4, None, None]

    If *n* is ``None``, *fillvalue* will be emitted indefinitely.

    To create an *iterable* of exactly size *n*, you can truncate with
    :func:`islice`.

        >>> list(islice(padded([1, 2, 3], '?'), 5))
        [1, 2, 3, '?', '?']
        >>> list(islice(padded([1, 2, 3, 4, 5, 6, 7, 8], '?'), 5))
        [1, 2, 3, 4, 5]

    """
    iterable = iter(iterable)
    iterable_with_repeat = chain(iterable, repeat(fillvalue))
    if n is None:
        return iterable_with_repeat
    elif n < 1:
        raise ValueError('n must be at least 1')
    elif next_multiple:

        def slice_generator():
            for first in iterable:
                yield (first,)
                yield islice(iterable_with_repeat, n - 1)
        return chain.from_iterable(slice_generator())
    else:
        return chain(islice(iterable_with_repeat, n), iterable)

def repeat_each(iterable, n=2):
    """Repeat each element in *iterable* *n* times.

    >>> list(repeat_each('ABC', 3))
    ['A', 'A', 'A', 'B', 'B', 'B', 'C', 'C', 'C']
    """
    return chain.from_iterable(map(repeat, iterable, repeat(n)))

def repeat_last(iterable, default=None):
    """After the *iterable* is exhausted, keep yielding its last element.

        >>> list(islice(repeat_last(range(3)), 5))
        [0, 1, 2, 2, 2]

    If the iterable is empty, yield *default* forever::

        >>> list(islice(repeat_last(range(0), 42), 5))
        [42, 42, 42, 42, 42]

    """
    item = _marker
    for item in iterable:
        yield item
    final = default if item is _marker else item
    yield from repeat(final)

def distribute(n, iterable):
    """Distribute the items from *iterable* among *n* smaller iterables.

        >>> group_1, group_2 = distribute(2, [1, 2, 3, 4, 5, 6])
        >>> list(group_1)
        [1, 3, 5]
        >>> list(group_2)
        [2, 4, 6]

    If the length of *iterable* is not evenly divisible by *n*, then the
    length of the returned iterables will not be identical:

        >>> children = distribute(3, [1, 2, 3, 4, 5, 6, 7])
        >>> [list(c) for c in children]
        [[1, 4, 7], [2, 5], [3, 6]]

    If the length of *iterable* is smaller than *n*, then the last returned
    iterables will be empty:

        >>> children = distribute(5, [1, 2, 3])
        >>> [list(c) for c in children]
        [[1], [2], [3], [], []]

    This function uses :func:`itertools.tee` and may require significant
    storage.

    If you need the order items in the smaller iterables to match the
    original iterable, see :func:`divide`.

    """
    if n < 1:
        raise ValueError('n must be at least 1')
    children = tee(iterable, n)
    return [islice(it, index, None, n) for index, it in enumerate(children)]

def stagger(iterable, offsets=(-1, 0, 1), longest=False, fillvalue=None):
    """Yield tuples whose elements are offset from *iterable*.
    The amount by which the `i`-th item in each tuple is offset is given by
    the `i`-th item in *offsets*.

        >>> list(stagger([0, 1, 2, 3]))
        [(None, 0, 1), (0, 1, 2), (1, 2, 3)]
        >>> list(stagger(range(8), offsets=(0, 2, 4)))
        [(0, 2, 4), (1, 3, 5), (2, 4, 6), (3, 5, 7)]

    By default, the sequence will end when the final element of a tuple is the
    last item in the iterable. To continue until the first element of a tuple
    is the last item in the iterable, set *longest* to ``True``::

        >>> list(stagger([0, 1, 2, 3], longest=True))
        [(None, 0, 1), (0, 1, 2), (1, 2, 3), (2, 3, None), (3, None, None)]

    By default, ``None`` will be used to replace offsets beyond the end of the
    sequence. Specify *fillvalue* to use some other value.

    """
    children = tee(iterable, len(offsets))
    return zip_offset(*children, offsets=offsets, longest=longest, fillvalue=fillvalue)

def zip_equal(*iterables):
    """``zip`` the input *iterables* together, but raise
    ``UnequalIterablesError`` if they aren't all the same length.

        >>> it_1 = range(3)
        >>> it_2 = iter('abc')
        >>> list(zip_equal(it_1, it_2))
        [(0, 'a'), (1, 'b'), (2, 'c')]

        >>> it_1 = range(3)
        >>> it_2 = iter('abcd')
        >>> list(zip_equal(it_1, it_2)) # doctest: +IGNORE_EXCEPTION_DETAIL
        Traceback (most recent call last):
        ...
        more_itertools.more.UnequalIterablesError: Iterables have different
        lengths

    """
    if hexversion >= 50987174:
        warnings.warn('zip_equal will be removed in a future version of more-itertools. Use the builtin zip function with strict=True instead.', DeprecationWarning)
    return _zip_equal(*iterables)

def zip_offset(*iterables, offsets, longest=False, fillvalue=None):
    """``zip`` the input *iterables* together, but offset the `i`-th iterable
    by the `i`-th item in *offsets*.

        >>> list(zip_offset('0123', 'abcdef', offsets=(0, 1)))
        [('0', 'b'), ('1', 'c'), ('2', 'd'), ('3', 'e')]

    This can be used as a lightweight alternative to SciPy or pandas to analyze
    data sets in which some series have a lead or lag relationship.

    By default, the sequence will end when the shortest iterable is exhausted.
    To continue until the longest iterable is exhausted, set *longest* to
    ``True``.

        >>> list(zip_offset('0123', 'abcdef', offsets=(0, 1), longest=True))
        [('0', 'b'), ('1', 'c'), ('2', 'd'), ('3', 'e'), (None, 'f')]

    By default, ``None`` will be used to replace offsets beyond the end of the
    sequence. Specify *fillvalue* to use some other value.

    """
    if len(iterables) != len(offsets):
        raise ValueError("Number of iterables and offsets didn't match")
    staggered = []
    for it, n in zip(iterables, offsets):
        if n < 0:
            staggered.append(chain(repeat(fillvalue, -n), it))
        elif n > 0:
            staggered.append(islice(it, n, None))
        else:
            staggered.append(it)
    if longest:
        return zip_longest(*staggered, fillvalue=fillvalue)
    return zip(*staggered)

def sort_together(iterables, key_list=(0,), key=None, reverse=False, strict=False):
    """Return the input iterables sorted together, with *key_list* as the
    priority for sorting. All iterables are trimmed to the length of the
    shortest one.

    This can be used like the sorting function in a spreadsheet. If each
    iterable represents a column of data, the key list determines which
    columns are used for sorting.

    By default, all iterables are sorted using the ``0``-th iterable::

        >>> iterables = [(4, 3, 2, 1), ('a', 'b', 'c', 'd')]
        >>> sort_together(iterables)
        [(1, 2, 3, 4), ('d', 'c', 'b', 'a')]

    Set a different key list to sort according to another iterable.
    Specifying multiple keys dictates how ties are broken::

        >>> iterables = [(3, 1, 2), (0, 1, 0), ('c', 'b', 'a')]
        >>> sort_together(iterables, key_list=(1, 2))
        [(2, 3, 1), (0, 0, 1), ('a', 'c', 'b')]

    To sort by a function of the elements of the iterable, pass a *key*
    function. Its arguments are the elements of the iterables corresponding to
    the key list::

        >>> names = ('a', 'b', 'c')
        >>> lengths = (1, 2, 3)
        >>> widths = (5, 2, 1)
        >>> def area(length, width):
        ...     return length * width
        >>> sort_together([names, lengths, widths], key_list=(1, 2), key=area)
        [('c', 'b', 'a'), (3, 2, 1), (1, 2, 5)]

    Set *reverse* to ``True`` to sort in descending order.

        >>> sort_together([(1, 2, 3), ('c', 'b', 'a')], reverse=True)
        [(3, 2, 1), ('a', 'b', 'c')]

    If the *strict* keyword argument is ``True``, then
    ``UnequalIterablesError`` will be raised if any of the iterables have
    different lengths.

    """
    if key is None:
        key_argument = itemgetter(*key_list)
    else:
        key_list = list(key_list)
        if len(key_list) == 1:
            key_offset = key_list[0]
            key_argument = lambda zipped_items: key(zipped_items[key_offset])
        else:
            get_key_items = itemgetter(*key_list)
            key_argument = lambda zipped_items: key(*get_key_items(zipped_items))
    zipper = zip_equal if strict else zip
    return list(zipper(*sorted(zipper(*iterables), key=key_argument, reverse=reverse)))

def divide(n, iterable):
    """Divide the elements from *iterable* into *n* parts, maintaining
    order.

        >>> group_1, group_2 = divide(2, [1, 2, 3, 4, 5, 6])
        >>> list(group_1)
        [1, 2, 3]
        >>> list(group_2)
        [4, 5, 6]

    If the length of *iterable* is not evenly divisible by *n*, then the
    length of the returned iterables will not be identical:

        >>> children = divide(3, [1, 2, 3, 4, 5, 6, 7])
        >>> [list(c) for c in children]
        [[1, 2, 3], [4, 5], [6, 7]]

    If the length of the iterable is smaller than n, then the last returned
    iterables will be empty:

        >>> children = divide(5, [1, 2, 3])
        >>> [list(c) for c in children]
        [[1], [2], [3], [], []]

    This function will exhaust the iterable before returning.
    If order is not important, see :func:`distribute`, which does not first
    pull the iterable into memory.

    """
    if n < 1:
        raise ValueError('n must be at least 1')
    try:
        iterable[:0]
    except TypeError:
        seq = tuple(iterable)
    else:
        seq = iterable
    q, r = divmod(len(seq), n)
    ret = []
    stop = 0
    for i in range(1, n + 1):
        start = stop
        stop += q + 1 if i <= r else q
        ret.append(iter(seq[start:stop]))
    return ret

def adjacent(predicate, iterable, distance=1):
    """Return an iterable over `(bool, item)` tuples where the `item` is
    drawn from *iterable* and the `bool` indicates whether
    that item satisfies the *predicate* or is adjacent to an item that does.

    For example, to find whether items are adjacent to a ``3``::

        >>> list(adjacent(lambda x: x == 3, range(6)))
        [(False, 0), (False, 1), (True, 2), (True, 3), (True, 4), (False, 5)]

    Set *distance* to change what counts as adjacent. For example, to find
    whether items are two places away from a ``3``:

        >>> list(adjacent(lambda x: x == 3, range(6), distance=2))
        [(False, 0), (True, 1), (True, 2), (True, 3), (True, 4), (True, 5)]

    This is useful for contextualizing the results of a search function.
    For example, a code comparison tool might want to identify lines that
    have changed, but also surrounding lines to give the viewer of the diff
    context.

    The predicate function will only be called once for each item in the
    iterable.

    See also :func:`groupby_transform`, which can be used with this function
    to group ranges of items with the same `bool` value.

    """
    if distance < 0:
        raise ValueError('distance must be at least 0')
    i1, i2 = tee(iterable)
    padding = [False] * distance
    selected = chain(padding, map(predicate, i1), padding)
    adjacent_to_selected = map(any, windowed(selected, 2 * distance + 1))
    return zip(adjacent_to_selected, i2)

def groupby_transform(iterable, keyfunc=None, valuefunc=None, reducefunc=None):
    """An extension of :func:`itertools.groupby` that can apply transformations
    to the grouped data.

    * *keyfunc* is a function computing a key value for each item in *iterable*
    * *valuefunc* is a function that transforms the individual items from
      *iterable* after grouping
    * *reducefunc* is a function that transforms each group of items

    >>> iterable = 'aAAbBBcCC'
    >>> keyfunc = lambda k: k.upper()
    >>> valuefunc = lambda v: v.lower()
    >>> reducefunc = lambda g: ''.join(g)
    >>> list(groupby_transform(iterable, keyfunc, valuefunc, reducefunc))
    [('A', 'aaa'), ('B', 'bbb'), ('C', 'ccc')]

    Each optional argument defaults to an identity function if not specified.

    :func:`groupby_transform` is useful when grouping elements of an iterable
    using a separate iterable as the key. To do this, :func:`zip` the iterables
    and pass a *keyfunc* that extracts the first element and a *valuefunc*
    that extracts the second element::

        >>> from operator import itemgetter
        >>> keys = [0, 0, 1, 1, 1, 2, 2, 2, 3]
        >>> values = 'abcdefghi'
        >>> iterable = zip(keys, values)
        >>> grouper = groupby_transform(iterable, itemgetter(0), itemgetter(1))
        >>> [(k, ''.join(g)) for k, g in grouper]
        [(0, 'ab'), (1, 'cde'), (2, 'fgh'), (3, 'i')]

    Note that the order of items in the iterable is significant.
    Only adjacent items are grouped together, so if you don't want any
    duplicate groups, you should sort the iterable by the key function.

    """
    ret = groupby(iterable, keyfunc)
    if valuefunc:
        ret = ((k, map(valuefunc, g)) for k, g in ret)
    if reducefunc:
        ret = ((k, reducefunc(g)) for k, g in ret)
    return ret

def mark_ends(iterable):
    """Yield 3-tuples of the form ``(is_first, is_last, item)``.

    >>> list(mark_ends('ABC'))
    [(True, False, 'A'), (False, False, 'B'), (False, True, 'C')]

    Use this when looping over an iterable to take special action on its first
    and/or last items:

    >>> iterable = ['Header', 100, 200, 'Footer']
    >>> total = 0
    >>> for is_first, is_last, item in mark_ends(iterable):
    ...     if is_first:
    ...         continue  # Skip the header
    ...     if is_last:
    ...         continue  # Skip the footer
    ...     total += item
    >>> print(total)
    300
    """
    it = iter(iterable)
    try:
        b = next(it)
    except StopIteration:
        return
    try:
        for i in count():
            a = b
            b = next(it)
            yield (i == 0, False, a)
    except StopIteration:
        yield (i == 0, True, a)

def locate(iterable, pred=bool, window_size=None):
    """Yield the index of each item in *iterable* for which *pred* returns
    ``True``.

    *pred* defaults to :func:`bool`, which will select truthy items:

        >>> list(locate([0, 1, 1, 0, 1, 0, 0]))
        [1, 2, 4]

    Set *pred* to a custom function to, e.g., find the indexes for a particular
    item.

        >>> list(locate(['a', 'b', 'c', 'b'], lambda x: x == 'b'))
        [1, 3]

    If *window_size* is given, then the *pred* function will be called with
    that many items. This enables searching for sub-sequences:

        >>> iterable = [0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3]
        >>> pred = lambda *args: args == (1, 2, 3)
        >>> list(locate(iterable, pred=pred, window_size=3))
        [1, 5, 9]

    Use with :func:`seekable` to find indexes and then retrieve the associated
    items:

        >>> from itertools import count
        >>> from more_itertools import seekable
        >>> source = (3 * n + 1 if (n % 2) else n // 2 for n in count())
        >>> it = seekable(source)
        >>> pred = lambda x: x > 100
        >>> indexes = locate(it, pred=pred)
        >>> i = next(indexes)
        >>> it.seek(i)
        >>> next(it)
        106

    """
    if window_size is None:
        return compress(count(), map(pred, iterable))
    if window_size < 1:
        raise ValueError('window size must be at least 1')
    it = windowed(iterable, window_size, fillvalue=_marker)
    return compress(count(), starmap(pred, it))

def longest_common_prefix(iterables):
    """Yield elements of the longest common prefix amongst given *iterables*.

    >>> ''.join(longest_common_prefix(['abcd', 'abc', 'abf']))
    'ab'

    """
    return (c[0] for c in takewhile(all_equal, zip(*iterables)))

def lstrip(iterable, pred):
    """Yield the items from *iterable*, but strip any from the beginning
    for which *pred* returns ``True``.

    For example, to remove a set of items from the start of an iterable:

        >>> iterable = (None, False, None, 1, 2, None, 3, False, None)
        >>> pred = lambda x: x in {None, False, ''}
        >>> list(lstrip(iterable, pred))
        [1, 2, None, 3, False, None]

    This function is analogous to to :func:`str.lstrip`, and is essentially
    an wrapper for :func:`itertools.dropwhile`.

    """
    return dropwhile(pred, iterable)

def rstrip(iterable, pred):
    """Yield the items from *iterable*, but strip any from the end
    for which *pred* returns ``True``.

    For example, to remove a set of items from the end of an iterable:

        >>> iterable = (None, False, None, 1, 2, None, 3, False, None)
        >>> pred = lambda x: x in {None, False, ''}
        >>> list(rstrip(iterable, pred))
        [None, False, None, 1, 2, None, 3]

    This function is analogous to :func:`str.rstrip`.

    """
    cache = []
    cache_append = cache.append
    cache_clear = cache.clear
    for x in iterable:
        if pred(x):
            cache_append(x)
        else:
            yield from cache
            cache_clear()
            yield x

def strip(iterable, pred):
    """Yield the items from *iterable*, but strip any from the
    beginning and end for which *pred* returns ``True``.

    For example, to remove a set of items from both ends of an iterable:

        >>> iterable = (None, False, None, 1, 2, None, 3, False, None)
        >>> pred = lambda x: x in {None, False, ''}
        >>> list(strip(iterable, pred))
        [1, 2, None, 3]

    This function is analogous to :func:`str.strip`.

    """
    return rstrip(lstrip(iterable, pred), pred)

def _islice_helper(it, s):
    start = s.start
    stop = s.stop
    if s.step == 0:
        raise ValueError('step argument must be a non-zero integer or None.')
    step = s.step or 1
    if step > 0:
        start = 0 if start is None else start
        if start < 0:
            cache = deque(enumerate(it, 1), maxlen=-start)
            len_iter = cache[-1][0] if cache else 0
            i = max(len_iter + start, 0)
            if stop is None:
                j = len_iter
            elif stop >= 0:
                j = min(stop, len_iter)
            else:
                j = max(len_iter + stop, 0)
            n = j - i
            if n <= 0:
                return
            for index, item in islice(cache, 0, n, step):
                yield item
        elif stop is not None and stop < 0:
            next(islice(it, start, start), None)
            cache = deque(islice(it, -stop), maxlen=-stop)
            for index, item in enumerate(it):
                cached_item = cache.popleft()
                if index % step == 0:
                    yield cached_item
                cache.append(item)
        else:
            yield from islice(it, start, stop, step)
    else:
        start = -1 if start is None else start
        if stop is not None and stop < 0:
            n = -stop - 1
            cache = deque(enumerate(it, 1), maxlen=n)
            len_iter = cache[-1][0] if cache else 0
            if start < 0:
                i, j = (start, stop)
            else:
                i, j = (min(start - len_iter, -1), None)
            for index, item in list(cache)[i:j:step]:
                yield item
        else:
            if stop is not None:
                m = stop + 1
                next(islice(it, m, m), None)
            if start < 0:
                i = start
                n = None
            elif stop is None:
                i = None
                n = start + 1
            else:
                i = None
                n = start - stop
                if n <= 0:
                    return
            cache = list(islice(it, n))
            yield from cache[i::step]

def always_reversible(iterable):
    """An extension of :func:`reversed` that supports all iterables, not
    just those which implement the ``Reversible`` or ``Sequence`` protocols.

        >>> print(*always_reversible(x for x in range(3)))
        2 1 0

    If the iterable is already reversible, this function returns the
    result of :func:`reversed()`. If the iterable is not reversible,
    this function will cache the remaining items in the iterable and
    yield them in reverse order, which may require significant storage.
    """
    try:
        return reversed(iterable)
    except TypeError:
        return reversed(list(iterable))

def consecutive_groups(iterable, ordering=lambda x: x):
    """Yield groups of consecutive items using :func:`itertools.groupby`.
    The *ordering* function determines whether two items are adjacent by
    returning their position.

    By default, the ordering function is the identity function. This is
    suitable for finding runs of numbers:

        >>> iterable = [1, 10, 11, 12, 20, 30, 31, 32, 33, 40]
        >>> for group in consecutive_groups(iterable):
        ...     print(list(group))
        [1]
        [10, 11, 12]
        [20]
        [30, 31, 32, 33]
        [40]

    For finding runs of adjacent letters, try using the :meth:`index` method
    of a string of letters:

        >>> from string import ascii_lowercase
        >>> iterable = 'abcdfgilmnop'
        >>> ordering = ascii_lowercase.index
        >>> for group in consecutive_groups(iterable, ordering):
        ...     print(list(group))
        ['a', 'b', 'c', 'd']
        ['f', 'g']
        ['i']
        ['l', 'm', 'n', 'o', 'p']

    Each group of consecutive items is an iterator that shares it source with
    *iterable*. When an an output group is advanced, the previous group is
    no longer available unless its elements are copied (e.g., into a ``list``).

        >>> iterable = [1, 2, 11, 12, 21, 22]
        >>> saved_groups = []
        >>> for group in consecutive_groups(iterable):
        ...     saved_groups.append(list(group))  # Copy group elements
        >>> saved_groups
        [[1, 2], [11, 12], [21, 22]]

    """
    for k, g in groupby(enumerate(iterable), key=lambda x: x[0] - ordering(x[1])):
        yield map(itemgetter(1), g)

def difference(iterable, func=sub, *, initial=None):
    """This function is the inverse of :func:`itertools.accumulate`. By default
    it will compute the first difference of *iterable* using
    :func:`operator.sub`:

        >>> from itertools import accumulate
        >>> iterable = accumulate([0, 1, 2, 3, 4])  # produces 0, 1, 3, 6, 10
        >>> list(difference(iterable))
        [0, 1, 2, 3, 4]

    *func* defaults to :func:`operator.sub`, but other functions can be
    specified. They will be applied as follows::

        A, B, C, D, ... --> A, func(B, A), func(C, B), func(D, C), ...

    For example, to do progressive division:

        >>> iterable = [1, 2, 6, 24, 120]
        >>> func = lambda x, y: x // y
        >>> list(difference(iterable, func))
        [1, 2, 3, 4, 5]

    If the *initial* keyword is set, the first element will be skipped when
    computing successive differences.

        >>> it = [10, 11, 13, 16]  # from accumulate([1, 2, 3], initial=10)
        >>> list(difference(it, initial=10))
        [1, 2, 3]

    """
    a, b = tee(iterable)
    try:
        first = [next(b)]
    except StopIteration:
        return iter([])
    if initial is not None:
        first = []
    return chain(first, map(func, b, a))

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

def circular_shifts(iterable, steps=1):
    """Yield the circular shifts of *iterable*.

    >>> list(circular_shifts(range(4)))
    [(0, 1, 2, 3), (1, 2, 3, 0), (2, 3, 0, 1), (3, 0, 1, 2)]

    Set *steps* to the number of places to rotate to the left
    (or to the right if negative).  Defaults to 1.

    >>> list(circular_shifts(range(4), 2))
    [(0, 1, 2, 3), (2, 3, 0, 1)]

    >>> list(circular_shifts(range(4), -1))
    [(0, 1, 2, 3), (3, 0, 1, 2), (2, 3, 0, 1), (1, 2, 3, 0)]

    """
    buffer = deque(iterable)
    if steps == 0:
        raise ValueError('Steps should be a non-zero integer')
    buffer.rotate(steps)
    steps = -steps
    n = len(buffer)
    n //= math.gcd(n, steps)
    for _ in repeat(None, n):
        buffer.rotate(steps)
        yield tuple(buffer)

def make_decorator(wrapping_func, result_index=0):
    """Return a decorator version of *wrapping_func*, which is a function that
    modifies an iterable. *result_index* is the position in that function's
    signature where the iterable goes.

    This lets you use itertools on the "production end," i.e. at function
    definition. This can augment what the function returns without changing the
    function's code.

    For example, to produce a decorator version of :func:`chunked`:

        >>> from more_itertools import chunked
        >>> chunker = make_decorator(chunked, result_index=0)
        >>> @chunker(3)
        ... def iter_range(n):
        ...     return iter(range(n))
        ...
        >>> list(iter_range(9))
        [[0, 1, 2], [3, 4, 5], [6, 7, 8]]

    To only allow truthy items to be returned:

        >>> truth_serum = make_decorator(filter, result_index=1)
        >>> @truth_serum(bool)
        ... def boolean_test():
        ...     return [0, 1, '', ' ', False, True]
        ...
        >>> list(boolean_test())
        [1, ' ', True]

    The :func:`peekable` and :func:`seekable` wrappers make for practical
    decorators:

        >>> from more_itertools import peekable
        >>> peekable_function = make_decorator(peekable)
        >>> @peekable_function()
        ... def str_range(*args):
        ...     return (str(x) for x in range(*args))
        ...
        >>> it = str_range(1, 20, 2)
        >>> next(it), next(it), next(it)
        ('1', '3', '5')
        >>> it.peek()
        '7'
        >>> next(it)
        '7'

    """

    def decorator(*wrapping_args, **wrapping_kwargs):

        def outer_wrapper(f):

            def inner_wrapper(*args, **kwargs):
                result = f(*args, **kwargs)
                wrapping_args_ = list(wrapping_args)
                wrapping_args_.insert(result_index, result)
                return wrapping_func(*wrapping_args_, **wrapping_kwargs)
            return inner_wrapper
        return outer_wrapper
    return decorator

def map_reduce(iterable, keyfunc, valuefunc=None, reducefunc=None):
    """Return a dictionary that maps the items in *iterable* to categories
    defined by *keyfunc*, transforms them with *valuefunc*, and
    then summarizes them by category with *reducefunc*.

    *valuefunc* defaults to the identity function if it is unspecified.
    If *reducefunc* is unspecified, no summarization takes place:

        >>> keyfunc = lambda x: x.upper()
        >>> result = map_reduce('abbccc', keyfunc)
        >>> sorted(result.items())
        [('A', ['a']), ('B', ['b', 'b']), ('C', ['c', 'c', 'c'])]

    Specifying *valuefunc* transforms the categorized items:

        >>> keyfunc = lambda x: x.upper()
        >>> valuefunc = lambda x: 1
        >>> result = map_reduce('abbccc', keyfunc, valuefunc)
        >>> sorted(result.items())
        [('A', [1]), ('B', [1, 1]), ('C', [1, 1, 1])]

    Specifying *reducefunc* summarizes the categorized items:

        >>> keyfunc = lambda x: x.upper()
        >>> valuefunc = lambda x: 1
        >>> reducefunc = sum
        >>> result = map_reduce('abbccc', keyfunc, valuefunc, reducefunc)
        >>> sorted(result.items())
        [('A', 1), ('B', 2), ('C', 3)]

    You may want to filter the input iterable before applying the map/reduce
    procedure:

        >>> all_items = range(30)
        >>> items = [x for x in all_items if 10 <= x <= 20]  # Filter
        >>> keyfunc = lambda x: x % 2  # Evens map to 0; odds to 1
        >>> categories = map_reduce(items, keyfunc=keyfunc)
        >>> sorted(categories.items())
        [(0, [10, 12, 14, 16, 18, 20]), (1, [11, 13, 15, 17, 19])]
        >>> summaries = map_reduce(items, keyfunc=keyfunc, reducefunc=sum)
        >>> sorted(summaries.items())
        [(0, 90), (1, 75)]

    Note that all items in the iterable are gathered into a list before the
    summarization step, which may require significant storage.

    The returned object is a :obj:`collections.defaultdict` with the
    ``default_factory`` set to ``None``, such that it behaves like a normal
    dictionary.

    """
    valuefunc = (lambda x: x) if valuefunc is None else valuefunc
    ret = defaultdict(list)
    for item in iterable:
        key = keyfunc(item)
        value = valuefunc(item)
        ret[key].append(value)
    if reducefunc is not None:
        for key, value_list in ret.items():
            ret[key] = reducefunc(value_list)
    ret.default_factory = None
    return ret

def rlocate(iterable, pred=bool, window_size=None):
    """Yield the index of each item in *iterable* for which *pred* returns
    ``True``, starting from the right and moving left.

    *pred* defaults to :func:`bool`, which will select truthy items:

        >>> list(rlocate([0, 1, 1, 0, 1, 0, 0]))  # Truthy at 1, 2, and 4
        [4, 2, 1]

    Set *pred* to a custom function to, e.g., find the indexes for a particular
    item:

        >>> iterable = iter('abcb')
        >>> pred = lambda x: x == 'b'
        >>> list(rlocate(iterable, pred))
        [3, 1]

    If *window_size* is given, then the *pred* function will be called with
    that many items. This enables searching for sub-sequences:

        >>> iterable = [0, 1, 2, 3, 0, 1, 2, 3, 0, 1, 2, 3]
        >>> pred = lambda *args: args == (1, 2, 3)
        >>> list(rlocate(iterable, pred=pred, window_size=3))
        [9, 5, 1]

    Beware, this function won't return anything for infinite iterables.
    If *iterable* is reversible, ``rlocate`` will reverse it and search from
    the right. Otherwise, it will search from the left and return the results
    in reverse order.

    See :func:`locate` to for other example applications.

    """
    if window_size is None:
        try:
            len_iter = len(iterable)
            return (len_iter - i - 1 for i in locate(reversed(iterable), pred))
        except TypeError:
            pass
    return reversed(list(locate(iterable, pred, window_size)))

def replace(iterable, pred, substitutes, count=None, window_size=1):
    """Yield the items from *iterable*, replacing the items for which *pred*
    returns ``True`` with the items from the iterable *substitutes*.

        >>> iterable = [1, 1, 0, 1, 1, 0, 1, 1]
        >>> pred = lambda x: x == 0
        >>> substitutes = (2, 3)
        >>> list(replace(iterable, pred, substitutes))
        [1, 1, 2, 3, 1, 1, 2, 3, 1, 1]

    If *count* is given, the number of replacements will be limited:

        >>> iterable = [1, 1, 0, 1, 1, 0, 1, 1, 0]
        >>> pred = lambda x: x == 0
        >>> substitutes = [None]
        >>> list(replace(iterable, pred, substitutes, count=2))
        [1, 1, None, 1, 1, None, 1, 1, 0]

    Use *window_size* to control the number of items passed as arguments to
    *pred*. This allows for locating and replacing subsequences.

        >>> iterable = [0, 1, 2, 5, 0, 1, 2, 5]
        >>> window_size = 3
        >>> pred = lambda *args: args == (0, 1, 2)  # 3 items passed to pred
        >>> substitutes = [3, 4] # Splice in these items
        >>> list(replace(iterable, pred, substitutes, window_size=window_size))
        [3, 4, 5, 3, 4, 5]

    """
    if window_size < 1:
        raise ValueError('window_size must be at least 1')
    substitutes = tuple(substitutes)
    it = chain(iterable, [_marker] * (window_size - 1))
    windows = windowed(it, window_size)
    n = 0
    for w in windows:
        if pred(*w):
            if count is None or n < count:
                n += 1
                yield from substitutes
                consume(windows, window_size - 1)
                continue
        if w and w[0] is not _marker:
            yield w[0]

def partitions(iterable):
    """Yield all possible order-preserving partitions of *iterable*.

    >>> iterable = 'abc'
    >>> for part in partitions(iterable):
    ...     print([''.join(p) for p in part])
    ['abc']
    ['a', 'bc']
    ['ab', 'c']
    ['a', 'b', 'c']

    This is unrelated to :func:`partition`.

    """
    sequence = list(iterable)
    n = len(sequence)
    for i in powerset(range(1, n)):
        yield [sequence[i:j] for i, j in zip((0,) + i, i + (n,))]

def set_partitions(iterable, k=None, min_size=None, max_size=None):
    """
    Yield the set partitions of *iterable* into *k* parts. Set partitions are
    not order-preserving.

    >>> iterable = 'abc'
    >>> for part in set_partitions(iterable, 2):
    ...     print([''.join(p) for p in part])
    ['a', 'bc']
    ['ab', 'c']
    ['b', 'ac']


    If *k* is not given, every set partition is generated.

    >>> iterable = 'abc'
    >>> for part in set_partitions(iterable):
    ...     print([''.join(p) for p in part])
    ['abc']
    ['a', 'bc']
    ['ab', 'c']
    ['b', 'ac']
    ['a', 'b', 'c']

    if *min_size* and/or *max_size* are given, the minimum and/or maximum size
    per block in partition is set.

    >>> iterable = 'abc'
    >>> for part in set_partitions(iterable, min_size=2):
    ...     print([''.join(p) for p in part])
    ['abc']
    >>> for part in set_partitions(iterable, max_size=2):
    ...     print([''.join(p) for p in part])
    ['a', 'bc']
    ['ab', 'c']
    ['b', 'ac']
    ['a', 'b', 'c']

    """
    L = list(iterable)
    n = len(L)
    if k is not None:
        if k < 1:
            raise ValueError("Can't partition in a negative or zero number of groups")
        elif k > n:
            return
    min_size = min_size if min_size is not None else 0
    max_size = max_size if max_size is not None else n
    if min_size > max_size:
        return

    def set_partitions_helper(L, k):
        n = len(L)
        if k == 1:
            yield [L]
        elif n == k:
            yield [[s] for s in L]
        else:
            e, *M = L
            for p in set_partitions_helper(M, k - 1):
                yield [[e], *p]
            for p in set_partitions_helper(M, k):
                for i in range(len(p)):
                    yield (p[:i] + [[e] + p[i]] + p[i + 1:])
    if k is None:
        for k in range(1, n + 1):
            yield from filter(lambda z: all((min_size <= len(bk) <= max_size for bk in z)), set_partitions_helper(L, k))
    else:
        yield from filter(lambda z: all((min_size <= len(bk) <= max_size for bk in z)), set_partitions_helper(L, k))

def only(iterable, default=None, too_long=None):
    """If *iterable* has only one item, return it.
    If it has zero items, return *default*.
    If it has more than one item, raise the exception given by *too_long*,
    which is ``ValueError`` by default.

    >>> only([], default='missing')
    'missing'
    >>> only([1])
    1
    >>> only([1, 2])  # doctest: +IGNORE_EXCEPTION_DETAIL
    Traceback (most recent call last):
    ...
    ValueError: Expected exactly one item in iterable, but got 1, 2,
     and perhaps more.'
    >>> only([1, 2], too_long=TypeError)  # doctest: +IGNORE_EXCEPTION_DETAIL
    Traceback (most recent call last):
    ...
    TypeError

    Note that :func:`only` attempts to advance *iterable* twice to ensure there
    is only one item.  See :func:`spy` or :func:`peekable` to check
    iterable contents less destructively.

    """
    iterator = iter(iterable)
    for first in iterator:
        for second in iterator:
            msg = f'Expected exactly one item in iterable, but got {first!r}, {second!r}, and perhaps more.'
            raise too_long or ValueError(msg)
        return first
    return default

def _ichunk(iterable, n):
    cache = deque()
    chunk = islice(iterable, n)

    def generator():
        with suppress(StopIteration):
            while True:
                if cache:
                    yield cache.popleft()
                else:
                    yield next(chunk)

    def materialize_next(n=1):
        if n is None:
            cache.extend(chunk)
            return len(cache)
        to_cache = n - len(cache)
        if to_cache > 0:
            cache.extend(islice(chunk, to_cache))
        return min(n, len(cache))
    return (generator(), materialize_next)

def ichunked(iterable, n):
    """Break *iterable* into sub-iterables with *n* elements each.
    :func:`ichunked` is like :func:`chunked`, but it yields iterables
    instead of lists.

    If the sub-iterables are read in order, the elements of *iterable*
    won't be stored in memory.
    If they are read out of order, :func:`itertools.tee` is used to cache
    elements as necessary.

    >>> from itertools import count
    >>> all_chunks = ichunked(count(), 4)
    >>> c_1, c_2, c_3 = next(all_chunks), next(all_chunks), next(all_chunks)
    >>> list(c_2)  # c_1's elements have been cached; c_3's haven't been
    [4, 5, 6, 7]
    >>> list(c_1)
    [0, 1, 2, 3]
    >>> list(c_3)
    [8, 9, 10, 11]

    """
    iterable = iter(iterable)
    while True:
        chunk, materialize_next = _ichunk(iterable, n)
        if not materialize_next():
            return
        yield chunk
        materialize_next(None)

def iequals(*iterables):
    """Return ``True`` if all given *iterables* are equal to each other,
    which means that they contain the same elements in the same order.

    The function is useful for comparing iterables of different data types
    or iterables that do not support equality checks.

    >>> iequals("abc", ['a', 'b', 'c'], ('a', 'b', 'c'), iter("abc"))
    True

    >>> iequals("abc", "acb")
    False

    Not to be confused with :func:`all_equal`, which checks whether all
    elements of iterable are equal to each other.

    """
    return all(map(all_equal, zip_longest(*iterables, fillvalue=object())))

def filter_except(validator, iterable, *exceptions):
    """Yield the items from *iterable* for which the *validator* function does
    not raise one of the specified *exceptions*.

    *validator* is called for each item in *iterable*.
    It should be a function that accepts one argument and raises an exception
    if that item is not valid.

    >>> iterable = ['1', '2', 'three', '4', None]
    >>> list(filter_except(int, iterable, ValueError, TypeError))
    ['1', '2', '4']

    If an exception other than one given by *exceptions* is raised by
    *validator*, it is raised like normal.
    """
    for item in iterable:
        try:
            validator(item)
        except exceptions:
            pass
        else:
            yield item

def map_except(function, iterable, *exceptions):
    """Transform each item from *iterable* with *function* and yield the
    result, unless *function* raises one of the specified *exceptions*.

    *function* is called to transform each item in *iterable*.
    It should accept one argument.

    >>> iterable = ['1', '2', 'three', '4', None]
    >>> list(map_except(int, iterable, ValueError, TypeError))
    [1, 2, 4]

    If an exception other than one given by *exceptions* is raised by
    *function*, it is raised like normal.
    """
    for item in iterable:
        try:
            yield function(item)
        except exceptions:
            pass

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

def _sample_unweighted(iterator, k, strict):
    reservoir = list(islice(iterator, k))
    if strict and len(reservoir) < k:
        raise ValueError('Sample larger than population')
    W = 1.0
    with suppress(StopIteration):
        while True:
            W *= exp(log(random()) / k)
            skip = floor(log(random()) / log1p(-W))
            element = next(islice(iterator, skip, None))
            reservoir[randrange(k)] = element
    shuffle(reservoir)
    return reservoir

def _sample_weighted(iterator, k, weights, strict):
    weight_keys = (log(random()) / weight for weight in weights)
    reservoir = take(k, zip(weight_keys, iterator))
    if strict and len(reservoir) < k:
        raise ValueError('Sample larger than population')
    heapify(reservoir)
    smallest_weight_key, _ = reservoir[0]
    weights_to_skip = log(random()) / smallest_weight_key
    for weight, element in zip(weights, iterator):
        if weight >= weights_to_skip:
            smallest_weight_key, _ = reservoir[0]
            t_w = exp(weight * smallest_weight_key)
            r_2 = uniform(t_w, 1)
            weight_key = log(r_2) / weight
            heapreplace(reservoir, (weight_key, element))
            smallest_weight_key, _ = reservoir[0]
            weights_to_skip = log(random()) / smallest_weight_key
        else:
            weights_to_skip -= weight
    ret = [element for weight_key, element in reservoir]
    shuffle(ret)
    return ret

def _sample_counted(population, k, counts, strict):
    element = None
    remaining = 0

    def feed(i):
        nonlocal element, remaining
        while i + 1 > remaining:
            i = i - remaining
            element = next(population)
            remaining = next(counts)
        remaining -= i + 1
        return element
    with suppress(StopIteration):
        reservoir = []
        for _ in range(k):
            reservoir.append(feed(0))
    if strict and len(reservoir) < k:
        raise ValueError('Sample larger than population')
    with suppress(StopIteration):
        W = 1.0
        while True:
            W *= exp(log(random()) / k)
            skip = floor(log(random()) / log1p(-W))
            element = feed(skip)
            reservoir[randrange(k)] = element
    shuffle(reservoir)
    return reservoir

def sample(iterable, k, weights=None, *, counts=None, strict=False):
    """Return a *k*-length list of elements chosen (without replacement)
    from the *iterable*. Similar to :func:`random.sample`, but works on
    iterables of unknown length.

    >>> iterable = range(100)
    >>> sample(iterable, 5)  # doctest: +SKIP
    [81, 60, 96, 16, 4]

    For iterables with repeated elements, you may supply *counts* to
    indicate the repeats.

    >>> iterable = ['a', 'b']
    >>> counts = [3, 4]  # Equivalent to 'a', 'a', 'a', 'b', 'b', 'b', 'b'
    >>> sample(iterable, k=3, counts=counts)  # doctest: +SKIP
    ['a', 'a', 'b']

    An iterable with *weights* may be given:

    >>> iterable = range(100)
    >>> weights = (i * i + 1 for i in range(100))
    >>> sampled = sample(iterable, 5, weights=weights)  # doctest: +SKIP
    [79, 67, 74, 66, 78]

    Weighted selections are made without replacement.
    After an element is selected, it is removed from the pool and the
    relative weights of the other elements increase (this
    does not match the behavior of :func:`random.sample`'s *counts*
    parameter). Note that *weights* may not be used with *counts*.

    If the length of *iterable* is less than *k*,
    ``ValueError`` is raised if *strict* is ``True`` and
    all elements are returned (in shuffled order) if *strict* is ``False``.

    By default, the `Algorithm L <https://w.wiki/ANrM>`__ reservoir sampling
    technique is used. When *weights* are provided,
    `Algorithm A-ExpJ <https://w.wiki/ANrS>`__ is used.
    """
    iterator = iter(iterable)
    if k < 0:
        raise ValueError('k must be non-negative')
    if k == 0:
        return []
    if weights is not None and counts is not None:
        raise TypeError('weights and counts are mutually exclusive')
    elif weights is not None:
        weights = iter(weights)
        return _sample_weighted(iterator, k, weights, strict)
    elif counts is not None:
        counts = iter(counts)
        return _sample_counted(iterator, k, counts, strict)
    else:
        return _sample_unweighted(iterator, k, strict)

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

def windowed_complete(iterable, n):
    """
    Yield ``(beginning, middle, end)`` tuples, where:

    * Each ``middle`` has *n* items from *iterable*
    * Each ``beginning`` has the items before the ones in ``middle``
    * Each ``end`` has the items after the ones in ``middle``

    >>> iterable = range(7)
    >>> n = 3
    >>> for beginning, middle, end in windowed_complete(iterable, n):
    ...     print(beginning, middle, end)
    () (0, 1, 2) (3, 4, 5, 6)
    (0,) (1, 2, 3) (4, 5, 6)
    (0, 1) (2, 3, 4) (5, 6)
    (0, 1, 2) (3, 4, 5) (6,)
    (0, 1, 2, 3) (4, 5, 6) ()

    Note that *n* must be at least 0 and most equal to the length of
    *iterable*.

    This function will exhaust the iterable and may require significant
    storage.
    """
    if n < 0:
        raise ValueError('n must be >= 0')
    seq = tuple(iterable)
    size = len(seq)
    if n > size:
        raise ValueError('n must be <= len(seq)')
    for i in range(size - n + 1):
        beginning = seq[:i]
        middle = seq[i:i + n]
        end = seq[i + n:]
        yield (beginning, middle, end)

def all_unique(iterable, key=None):
    """
    Returns ``True`` if all the elements of *iterable* are unique (no two
    elements are equal).

        >>> all_unique('ABCB')
        False

    If a *key* function is specified, it will be used to make comparisons.

        >>> all_unique('ABCb')
        True
        >>> all_unique('ABCb', str.lower)
        False

    The function returns as soon as the first non-unique element is
    encountered. Iterables with a mix of hashable and unhashable items can
    be used, but the function will be slower for unhashable items.
    """
    seenset = set()
    seenset_add = seenset.add
    seenlist = []
    seenlist_add = seenlist.append
    for element in map(key, iterable) if key else iterable:
        try:
            if element in seenset:
                return False
            seenset_add(element)
        except TypeError:
            if element in seenlist:
                return False
            seenlist_add(element)
    return True

def nth_product(index, *args):
    """Equivalent to ``list(product(*args))[index]``.

    The products of *args* can be ordered lexicographically.
    :func:`nth_product` computes the product at sort position *index* without
    computing the previous products.

        >>> nth_product(8, range(2), range(2), range(2), range(2))
        (1, 0, 0, 0)

    ``IndexError`` will be raised if the given *index* is invalid.
    """
    pools = list(map(tuple, reversed(args)))
    ns = list(map(len, pools))
    c = reduce(mul, ns)
    if index < 0:
        index += c
    if not 0 <= index < c:
        raise IndexError
    result = []
    for pool, n in zip(pools, ns):
        result.append(pool[index % n])
        index //= n
    return tuple(reversed(result))

def nth_permutation(iterable, r, index):
    """Equivalent to ``list(permutations(iterable, r))[index]```

    The subsequences of *iterable* that are of length *r* where order is
    important can be ordered lexicographically. :func:`nth_permutation`
    computes the subsequence at sort position *index* directly, without
    computing the previous subsequences.

        >>> nth_permutation('ghijk', 2, 5)
        ('h', 'i')

    ``ValueError`` will be raised If *r* is negative or greater than the length
    of *iterable*.
    ``IndexError`` will be raised if the given *index* is invalid.
    """
    pool = list(iterable)
    n = len(pool)
    if r is None or r == n:
        r, c = (n, factorial(n))
    elif not 0 <= r < n:
        raise ValueError
    else:
        c = perm(n, r)
    assert c > 0
    if index < 0:
        index += c
    if not 0 <= index < c:
        raise IndexError
    result = [0] * r
    q = index * factorial(n) // c if r < n else index
    for d in range(1, n + 1):
        q, i = divmod(q, d)
        if 0 <= n - d < r:
            result[n - d] = i
        if q == 0:
            break
    return tuple(map(pool.pop, result))

def nth_combination_with_replacement(iterable, r, index):
    """Equivalent to
    ``list(combinations_with_replacement(iterable, r))[index]``.


    The subsequences with repetition of *iterable* that are of length *r* can
    be ordered lexicographically. :func:`nth_combination_with_replacement`
    computes the subsequence at sort position *index* directly, without
    computing the previous subsequences with replacement.

        >>> nth_combination_with_replacement(range(5), 3, 5)
        (0, 1, 1)

    ``ValueError`` will be raised If *r* is negative or greater than the length
    of *iterable*.
    ``IndexError`` will be raised if the given *index* is invalid.
    """
    pool = tuple(iterable)
    n = len(pool)
    if r < 0 or r > n:
        raise ValueError
    c = comb(n + r - 1, r)
    if index < 0:
        index += c
    if index < 0 or index >= c:
        raise IndexError
    result = []
    i = 0
    while r:
        r -= 1
        while n >= 0:
            num_combs = comb(n + r - 1, r)
            if index < num_combs:
                break
            n -= 1
            i += 1
            index -= num_combs
        result.append(pool[i])
    return tuple(result)

def value_chain(*args):
    """Yield all arguments passed to the function in the same order in which
    they were passed. If an argument itself is iterable then iterate over its
    values.

        >>> list(value_chain(1, 2, 3, [4, 5, 6]))
        [1, 2, 3, 4, 5, 6]

    Binary and text strings are not considered iterable and are emitted
    as-is:

        >>> list(value_chain('12', '34', ['56', '78']))
        ['12', '34', '56', '78']

    Pre- or postpend a single element to an iterable:

        >>> list(value_chain(1, [2, 3, 4, 5, 6]))
        [1, 2, 3, 4, 5, 6]
        >>> list(value_chain([1, 2, 3, 4, 5], 6))
        [1, 2, 3, 4, 5, 6]

    Multiple levels of nesting are not flattened.

    """
    for value in args:
        if isinstance(value, (str, bytes)):
            yield value
            continue
        try:
            yield from value
        except TypeError:
            yield value

def product_index(element, *args):
    """Equivalent to ``list(product(*args)).index(element)``

    The products of *args* can be ordered lexicographically.
    :func:`product_index` computes the first index of *element* without
    computing the previous products.

        >>> product_index([8, 2], range(10), range(5))
        42

    ``ValueError`` will be raised if the given *element* isn't in the product
    of *args*.
    """
    index = 0
    for x, pool in zip_longest(element, args, fillvalue=_marker):
        if x is _marker or pool is _marker:
            raise ValueError('element is not a product of args')
        pool = tuple(pool)
        index = index * len(pool) + pool.index(x)
    return index

def combination_index(element, iterable):
    """Equivalent to ``list(combinations(iterable, r)).index(element)``

    The subsequences of *iterable* that are of length *r* can be ordered
    lexicographically. :func:`combination_index` computes the index of the
    first *element*, without computing the previous combinations.

        >>> combination_index('adf', 'abcdefg')
        10

    ``ValueError`` will be raised if the given *element* isn't one of the
    combinations of *iterable*.
    """
    element = enumerate(element)
    k, y = next(element, (None, None))
    if k is None:
        return 0
    indexes = []
    pool = enumerate(iterable)
    for n, x in pool:
        if x == y:
            indexes.append(n)
            tmp, y = next(element, (None, None))
            if tmp is None:
                break
            else:
                k = tmp
    else:
        raise ValueError('element is not a combination of iterable')
    n, _ = last(pool, default=(n, None))
    index = 1
    for i, j in enumerate(reversed(indexes), start=1):
        j = n - j
        if i <= j:
            index += comb(j, i)
    return comb(n + 1, k + 1) - index

def combination_with_replacement_index(element, iterable):
    """Equivalent to
    ``list(combinations_with_replacement(iterable, r)).index(element)``

    The subsequences with repetition of *iterable* that are of length *r* can
    be ordered lexicographically. :func:`combination_with_replacement_index`
    computes the index of the first *element*, without computing the previous
    combinations with replacement.

        >>> combination_with_replacement_index('adf', 'abcdefg')
        20

    ``ValueError`` will be raised if the given *element* isn't one of the
    combinations with replacement of *iterable*.
    """
    element = tuple(element)
    l = len(element)
    element = enumerate(element)
    k, y = next(element, (None, None))
    if k is None:
        return 0
    indexes = []
    pool = tuple(iterable)
    for n, x in enumerate(pool):
        while x == y:
            indexes.append(n)
            tmp, y = next(element, (None, None))
            if tmp is None:
                break
            else:
                k = tmp
        if y is None:
            break
    else:
        raise ValueError('element is not a combination with replacement of iterable')
    n = len(pool)
    occupations = [0] * n
    for p in indexes:
        occupations[p] += 1
    index = 0
    cumulative_sum = 0
    for k in range(1, n):
        cumulative_sum += occupations[k - 1]
        j = l + n - 1 - k - cumulative_sum
        i = n - k
        if i <= j:
            index += comb(j, i)
    return index

def permutation_index(element, iterable):
    """Equivalent to ``list(permutations(iterable, r)).index(element)```

    The subsequences of *iterable* that are of length *r* where order is
    important can be ordered lexicographically. :func:`permutation_index`
    computes the index of the first *element* directly, without computing
    the previous permutations.

        >>> permutation_index([1, 3, 2], range(5))
        19

    ``ValueError`` will be raised if the given *element* isn't one of the
    permutations of *iterable*.
    """
    index = 0
    pool = list(iterable)
    for i, x in zip(range(len(pool), -1, -1), element):
        r = pool.index(x)
        index = index * i + r
        del pool[r]
    return index

def chunked_even(iterable, n):
    """Break *iterable* into lists of approximately length *n*.
    Items are distributed such the lengths of the lists differ by at most
    1 item.

    >>> iterable = [1, 2, 3, 4, 5, 6, 7]
    >>> n = 3
    >>> list(chunked_even(iterable, n))  # List lengths: 3, 2, 2
    [[1, 2, 3], [4, 5], [6, 7]]
    >>> list(chunked(iterable, n))  # List lengths: 3, 3, 1
    [[1, 2, 3], [4, 5, 6], [7]]

    """
    iterable = iter(iterable)
    min_buffer = (n - 1) * (n - 2)
    buffer = list(islice(iterable, min_buffer))
    for _ in islice(map(buffer.append, iterable), n, None, n):
        yield buffer[:n]
        del buffer[:n]
    if not buffer:
        return
    length = len(buffer)
    q, r = divmod(length, n)
    num_lists = q + (1 if r > 0 else 0)
    q, r = divmod(length, num_lists)
    full_size = q + (1 if r > 0 else 0)
    partial_size = full_size - 1
    num_full = length - partial_size * num_lists
    partial_start_idx = num_full * full_size
    if full_size > 0:
        for i in range(0, partial_start_idx, full_size):
            yield buffer[i:i + full_size]
    if partial_size > 0:
        for i in range(partial_start_idx, length, partial_size):
            yield buffer[i:i + partial_size]

def zip_broadcast(*objects, scalar_types=(str, bytes), strict=False):
    """A version of :func:`zip` that "broadcasts" any scalar
    (i.e., non-iterable) items into output tuples.

    >>> iterable_1 = [1, 2, 3]
    >>> iterable_2 = ['a', 'b', 'c']
    >>> scalar = '_'
    >>> list(zip_broadcast(iterable_1, iterable_2, scalar))
    [(1, 'a', '_'), (2, 'b', '_'), (3, 'c', '_')]

    The *scalar_types* keyword argument determines what types are considered
    scalar. It is set to ``(str, bytes)`` by default. Set it to ``None`` to
    treat strings and byte strings as iterable:

    >>> list(zip_broadcast('abc', 0, 'xyz', scalar_types=None))
    [('a', 0, 'x'), ('b', 0, 'y'), ('c', 0, 'z')]

    If the *strict* keyword argument is ``True``, then
    ``UnequalIterablesError`` will be raised if any of the iterables have
    different lengths.
    """

    def is_scalar(obj):
        if scalar_types and isinstance(obj, scalar_types):
            return True
        try:
            iter(obj)
        except TypeError:
            return True
        else:
            return False
    size = len(objects)
    if not size:
        return
    new_item = [None] * size
    iterables, iterable_positions = ([], [])
    for i, obj in enumerate(objects):
        if is_scalar(obj):
            new_item[i] = obj
        else:
            iterables.append(iter(obj))
            iterable_positions.append(i)
    if not iterables:
        yield tuple(objects)
        return
    zipper = _zip_equal if strict else zip
    for item in zipper(*iterables):
        for i, new_item[i] in zip(iterable_positions, item):
            pass
        yield tuple(new_item)

def unique_in_window(iterable, n, key=None):
    """Yield the items from *iterable* that haven't been seen recently.
    *n* is the size of the lookback window.

        >>> iterable = [0, 1, 0, 2, 3, 0]
        >>> n = 3
        >>> list(unique_in_window(iterable, n))
        [0, 1, 2, 3, 0]

    The *key* function, if provided, will be used to determine uniqueness:

        >>> list(unique_in_window('abAcda', 3, key=lambda x: x.lower()))
        ['a', 'b', 'c', 'd', 'a']

    The items in *iterable* must be hashable.

    """
    if n <= 0:
        raise ValueError('n must be greater than 0')
    window = deque(maxlen=n)
    counts = defaultdict(int)
    use_key = key is not None
    for item in iterable:
        if len(window) == n:
            to_discard = window[0]
            if counts[to_discard] == 1:
                del counts[to_discard]
            else:
                counts[to_discard] -= 1
        k = key(item) if use_key else item
        if k not in counts:
            yield item
        counts[k] += 1
        window.append(k)

def duplicates_everseen(iterable, key=None):
    """Yield duplicate elements after their first appearance.

    >>> list(duplicates_everseen('mississippi'))
    ['s', 'i', 's', 's', 'i', 'p', 'i']
    >>> list(duplicates_everseen('AaaBbbCccAaa', str.lower))
    ['a', 'a', 'b', 'b', 'c', 'c', 'A', 'a', 'a']

    This function is analogous to :func:`unique_everseen` and is subject to
    the same performance considerations.

    """
    seen_set = set()
    seen_list = []
    use_key = key is not None
    for element in iterable:
        k = key(element) if use_key else element
        try:
            if k not in seen_set:
                seen_set.add(k)
            else:
                yield element
        except TypeError:
            if k not in seen_list:
                seen_list.append(k)
            else:
                yield element

def duplicates_justseen(iterable, key=None):
    """Yields serially-duplicate elements after their first appearance.

    >>> list(duplicates_justseen('mississippi'))
    ['s', 's', 'p']
    >>> list(duplicates_justseen('AaaBbbCccAaa', str.lower))
    ['a', 'a', 'b', 'b', 'c', 'c', 'a', 'a']

    This function is analogous to :func:`unique_justseen`.

    """
    return flatten((g for _, g in groupby(iterable, key) for _ in g))

def classify_unique(iterable, key=None):
    """Classify each element in terms of its uniqueness.

    For each element in the input iterable, return a 3-tuple consisting of:

    1. The element itself
    2. ``False`` if the element is equal to the one preceding it in the input,
       ``True`` otherwise (i.e. the equivalent of :func:`unique_justseen`)
    3. ``False`` if this element has been seen anywhere in the input before,
       ``True`` otherwise (i.e. the equivalent of :func:`unique_everseen`)

    >>> list(classify_unique('otto'))    # doctest: +NORMALIZE_WHITESPACE
    [('o', True,  True),
     ('t', True,  True),
     ('t', False, False),
     ('o', True,  False)]

    This function is analogous to :func:`unique_everseen` and is subject to
    the same performance considerations.

    """
    seen_set = set()
    seen_list = []
    use_key = key is not None
    previous = None
    for i, element in enumerate(iterable):
        k = key(element) if use_key else element
        is_unique_justseen = not i or previous != k
        previous = k
        is_unique_everseen = False
        try:
            if k not in seen_set:
                seen_set.add(k)
                is_unique_everseen = True
        except TypeError:
            if k not in seen_list:
                seen_list.append(k)
                is_unique_everseen = True
        yield (element, is_unique_justseen, is_unique_everseen)

def minmax(iterable_or_value, *others, key=None, default=_marker):
    """Returns both the smallest and largest items in an iterable
    or the largest of two or more arguments.

        >>> minmax([3, 1, 5])
        (1, 5)

        >>> minmax(4, 2, 6)
        (2, 6)

    If a *key* function is provided, it will be used to transform the input
    items for comparison.

        >>> minmax([5, 30], key=str)  # '30' sorts before '5'
        (30, 5)

    If a *default* value is provided, it will be returned if there are no
    input items.

        >>> minmax([], default=(0, 0))
        (0, 0)

    Otherwise ``ValueError`` is raised.

    This function is based on the
    `recipe <http://code.activestate.com/recipes/577916/>`__ by
    Raymond Hettinger and takes care to minimize the number of comparisons
    performed.
    """
    iterable = (iterable_or_value, *others) if others else iterable_or_value
    it = iter(iterable)
    try:
        lo = hi = next(it)
    except StopIteration as exc:
        if default is _marker:
            raise ValueError('`minmax()` argument is an empty iterable. Provide a `default` value to suppress this error.') from exc
        return default
    if key is None:
        for x, y in zip_longest(it, it, fillvalue=lo):
            if y < x:
                x, y = (y, x)
            if x < lo:
                lo = x
            if hi < y:
                hi = y
    else:
        lo_key = hi_key = key(lo)
        for x, y in zip_longest(it, it, fillvalue=lo):
            x_key, y_key = (key(x), key(y))
            if y_key < x_key:
                x, y, x_key, y_key = (y, x, y_key, x_key)
            if x_key < lo_key:
                lo, lo_key = (x, x_key)
            if hi_key < y_key:
                hi, hi_key = (y, y_key)
    return (lo, hi)

def constrained_batches(iterable, max_size, max_count=None, get_len=len, strict=True):
    """Yield batches of items from *iterable* with a combined size limited by
    *max_size*.

    >>> iterable = [b'12345', b'123', b'12345678', b'1', b'1', b'12', b'1']
    >>> list(constrained_batches(iterable, 10))
    [(b'12345', b'123'), (b'12345678', b'1', b'1'), (b'12', b'1')]

    If a *max_count* is supplied, the number of items per batch is also
    limited:

    >>> iterable = [b'12345', b'123', b'12345678', b'1', b'1', b'12', b'1']
    >>> list(constrained_batches(iterable, 10, max_count = 2))
    [(b'12345', b'123'), (b'12345678', b'1'), (b'1', b'12'), (b'1',)]

    If a *get_len* function is supplied, use that instead of :func:`len` to
    determine item size.

    If *strict* is ``True``, raise ``ValueError`` if any single item is bigger
    than *max_size*. Otherwise, allow single items to exceed *max_size*.
    """
    if max_size <= 0:
        raise ValueError('maximum size must be greater than zero')
    batch = []
    batch_size = 0
    batch_count = 0
    for item in iterable:
        item_len = get_len(item)
        if strict and item_len > max_size:
            raise ValueError('item size exceeds maximum size')
        reached_count = batch_count == max_count
        reached_size = item_len + batch_size > max_size
        if batch_count and (reached_size or reached_count):
            yield tuple(batch)
            batch.clear()
            batch_size = 0
            batch_count = 0
        batch.append(item)
        batch_size += item_len
        batch_count += 1
    if batch:
        yield tuple(batch)

def gray_product(*iterables):
    """Like :func:`itertools.product`, but return tuples in an order such
    that only one element in the generated tuple changes from one iteration
    to the next.

        >>> list(gray_product('AB','CD'))
        [('A', 'C'), ('B', 'C'), ('B', 'D'), ('A', 'D')]

    This function consumes all of the input iterables before producing output.
    If any of the input iterables have fewer than two items, ``ValueError``
    is raised.

    For information on the algorithm, see
    `this section <https://www-cs-faculty.stanford.edu/~knuth/fasc2a.ps.gz>`__
    of Donald Knuth's *The Art of Computer Programming*.
    """
    all_iterables = tuple((tuple(x) for x in iterables))
    iterable_count = len(all_iterables)
    for iterable in all_iterables:
        if len(iterable) < 2:
            raise ValueError('each iterable must have two or more items')
    a = [0] * iterable_count
    f = list(range(iterable_count + 1))
    o = [1] * iterable_count
    while True:
        yield tuple((all_iterables[i][a[i]] for i in range(iterable_count)))
        j = f[0]
        f[0] = 0
        if j == iterable_count:
            break
        a[j] = a[j] + o[j]
        if a[j] == 0 or a[j] == len(all_iterables[j]) - 1:
            o[j] = -o[j]
            f[j] = f[j + 1]
            f[j + 1] = j + 1

def partial_product(*iterables):
    """Yields tuples containing one item from each iterator, with subsequent
    tuples changing a single item at a time by advancing each iterator until it
    is exhausted. This sequence guarantees every value in each iterable is
    output at least once without generating all possible combinations.

    This may be useful, for example, when testing an expensive function.

        >>> list(partial_product('AB', 'C', 'DEF'))
        [('A', 'C', 'D'), ('B', 'C', 'D'), ('B', 'C', 'E'), ('B', 'C', 'F')]
    """
    iterators = list(map(iter, iterables))
    try:
        prod = [next(it) for it in iterators]
    except StopIteration:
        return
    yield tuple(prod)
    for i, it in enumerate(iterators):
        for prod[i] in it:
            yield tuple(prod)

def takewhile_inclusive(predicate, iterable):
    """A variant of :func:`takewhile` that yields one additional element.

        >>> list(takewhile_inclusive(lambda x: x < 5, [1, 4, 6, 4, 1]))
        [1, 4, 6]

    :func:`takewhile` would return ``[1, 4]``.
    """
    for x in iterable:
        yield x
        if not predicate(x):
            break

def outer_product(func, xs, ys, *args, **kwargs):
    """A generalized outer product that applies a binary function to all
    pairs of items. Returns a 2D matrix with ``len(xs)`` rows and ``len(ys)``
    columns.
    Also accepts ``*args`` and ``**kwargs`` that are passed to ``func``.

    Multiplication table:

    >>> list(outer_product(mul, range(1, 4), range(1, 6)))
    [(1, 2, 3, 4, 5), (2, 4, 6, 8, 10), (3, 6, 9, 12, 15)]

    Cross tabulation:

    >>> xs = ['A', 'B', 'A', 'A', 'B', 'B', 'A', 'A', 'B', 'B']
    >>> ys = ['X', 'X', 'X', 'Y', 'Z', 'Z', 'Y', 'Y', 'Z', 'Z']
    >>> pair_counts = Counter(zip(xs, ys))
    >>> count_rows = lambda x, y: pair_counts[x, y]
    >>> list(outer_product(count_rows, sorted(set(xs)), sorted(set(ys))))
    [(2, 3, 0), (1, 0, 4)]

    Usage with ``*args`` and ``**kwargs``:

    >>> animals = ['cat', 'wolf', 'mouse']
    >>> list(outer_product(min, animals, animals, key=len))
    [('cat', 'cat', 'cat'), ('cat', 'wolf', 'wolf'), ('cat', 'wolf', 'mouse')]
    """
    ys = tuple(ys)
    return batched(starmap(lambda x, y: func(x, y, *args, **kwargs), product(xs, ys)), n=len(ys))

def iter_suppress(iterable, *exceptions):
    """Yield each of the items from *iterable*. If the iteration raises one of
    the specified *exceptions*, that exception will be suppressed and iteration
    will stop.

    >>> from itertools import chain
    >>> def breaks_at_five(x):
    ...     while True:
    ...         if x >= 5:
    ...             raise RuntimeError
    ...         yield x
    ...         x += 1
    >>> it_1 = iter_suppress(breaks_at_five(1), RuntimeError)
    >>> it_2 = iter_suppress(breaks_at_five(2), RuntimeError)
    >>> list(chain(it_1, it_2))
    [1, 2, 3, 4, 2, 3, 4]
    """
    try:
        yield from iterable
    except exceptions:
        return

def filter_map(func, iterable):
    """Apply *func* to every element of *iterable*, yielding only those which
    are not ``None``.

    >>> elems = ['1', 'a', '2', 'b', '3']
    >>> list(filter_map(lambda s: int(s) if s.isnumeric() else None, elems))
    [1, 2, 3]
    """
    for x in iterable:
        y = func(x)
        if y is not None:
            yield y

def powerset_of_sets(iterable):
    """Yields all possible subsets of the iterable.

        >>> list(powerset_of_sets([1, 2, 3]))  # doctest: +SKIP
        [set(), {1}, {2}, {3}, {1, 2}, {1, 3}, {2, 3}, {1, 2, 3}]
        >>> list(powerset_of_sets([1, 1, 0]))  # doctest: +SKIP
        [set(), {1}, {0}, {0, 1}]

    :func:`powerset_of_sets` takes care to minimize the number
    of hash operations performed.
    """
    sets = tuple(dict.fromkeys(map(frozenset, zip(iterable))))
    return chain.from_iterable((starmap(set().union, combinations(sets, r)) for r in range(len(sets) + 1)))

def join_mappings(**field_to_map):
    """
    Joins multiple mappings together using their common keys.

    >>> user_scores = {'elliot': 50, 'claris': 60}
    >>> user_times = {'elliot': 30, 'claris': 40}
    >>> join_mappings(score=user_scores, time=user_times)
    {'elliot': {'score': 50, 'time': 30}, 'claris': {'score': 60, 'time': 40}}
    """
    ret = defaultdict(dict)
    for field_name, mapping in field_to_map.items():
        for key, value in mapping.items():
            ret[key][field_name] = value
    return dict(ret)

def _complex_sumprod(v1, v2):
    """High precision sumprod() for complex numbers.
    Used by :func:`dft` and :func:`idft`.
    """
    r1 = chain((p.real for p in v1), (-p.imag for p in v1))
    r2 = chain((q.real for q in v2), (q.imag for q in v2))
    i1 = chain((p.real for p in v1), (p.imag for p in v1))
    i2 = chain((q.imag for q in v2), (q.real for q in v2))
    return complex(_fsumprod(r1, r2), _fsumprod(i1, i2))

def doublestarmap(func, iterable):
    """Apply *func* to every item of *iterable* by dictionary unpacking
    the item into *func*.

    The difference between :func:`itertools.starmap` and :func:`doublestarmap`
    parallels the distinction between ``func(*a)`` and ``func(**a)``.

    >>> iterable = [{'a': 1, 'b': 2}, {'a': 40, 'b': 60}]
    >>> list(doublestarmap(lambda a, b: a + b, iterable))
    [3, 100]

    ``TypeError`` will be raised if *func*'s signature doesn't match the
    mapping contained in *iterable* or if *iterable* does not contain mappings.
    """
    for item in iterable:
        yield func(**item)

def _nth_prime_ub(n):
    """Upper bound for the nth prime (counting from 1)."""
    return n * log(n * log(n)) if n >= 6 else 11.1

def nth_prime(n):
    """Return the nth prime (counting from 0).

    >>> nth_prime(0)
    2
    >>> nth_prime(100)
    547
    """
    if n < 0:
        raise ValueError
    limit = math.ceil(_nth_prime_ub(n + 1))
    return nth(sieve(limit), n)
