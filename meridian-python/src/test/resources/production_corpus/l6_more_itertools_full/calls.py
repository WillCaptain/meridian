# Coverage usage — facade hot path + convertible mi_more_full slice.
from itertools import islice
from mi_more_full import (
    chunked, first, last, ilen, one, iterate, repeat_each,
    collapse, padded, interleave, spy, exactly_n, is_sorted,
    map_if, unique_to_each, distribute, split_into,
)
from mi_facade import (
    take_list, take_sum, nth_item, all_equal_seq, quantify_bool, dotproduct_seq,
    first_true_val, ilen_seq, ncycles_list, flatten_list, chunked_lens,
)

nums = list(range(32))
flags = [True, False, True, True]
a = [10.0, 15.0, 12.0]
b = [0.65, 0.80, 1.25]

_ = take_list(3, nums)
_ = take_sum(5, nums)
_ = nth_item(nums, 3)
_ = all_equal_seq([3, 3, 3])
_ = quantify_bool(flags)
_ = dotproduct_seq(a, b)
_ = first_true_val([0, 0, 7])
_ = ilen_seq(nums)
_ = ncycles_list([1, 2], 2)
_ = flatten_list([[0, 1], [2]])
_ = chunked_lens(nums, 3)

_ = list(chunked(nums, 3))
_ = first(nums)
_ = last(nums)
_ = ilen(nums)
_ = one([9])
_ = list(islice(iterate(lambda x: x + 1, 0), 3))
_ = list(repeat_each([1, 2], 2))
_ = list(collapse([[1], [2, 3]]))
_ = list(padded([1, 2], fillvalue=0, n=4))
_ = list(interleave([1, 2], [3, 4]))
_ = spy(nums, 2)
_ = exactly_n([1, 0, 1], 2)
_ = is_sorted([1, 2, 3])
_ = list(map_if([-1, 2], lambda x: x > 0, lambda x: x * 2))
_ = list(unique_to_each([1, 2], [2, 3]))
_ = list(distribute(2, nums))
_ = list(split_into(nums, [2, 3]))
