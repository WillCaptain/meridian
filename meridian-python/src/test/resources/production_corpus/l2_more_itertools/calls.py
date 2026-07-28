# Usage evidence — mirrors typical doctest / call-site shapes for the recipes.

data = [0, 1, 0, 1, 1, 0, 1, 1, 0, 1]
zeros = [0, 0, 0, 0, 0, 7, 0]
same = [3, 3, 3, 3, 3]
a = [1.0, 2.0, 3.0, 4.0, 5.0]
b = [0.5, 0.5, 0.5, 0.5, 0.5]
nums = [1, 2, 3, 4, 5, 6, 7, 8]

_ = quantify(data, 10)
_ = nth(nums, 3)
_ = take_sum(5, nums)
_ = all_equal(same, 5)
_ = all_equal(nums, 5)
_ = dotproduct(a, b, 5)
_ = ncycles_sum(nums, 3, 8)
_ = first_true_index(zeros, 7)
_ = pairwise_product_sum(nums, 8)
_ = consume_count(nums, 8)
_ = tabulate_sum(100, 1)

def _inc(v):
    return v + 1

_ = apply_twice(_inc, 10)
