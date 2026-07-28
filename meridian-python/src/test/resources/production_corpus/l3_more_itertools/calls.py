from mi_facade import (
    quantify,
    take_sum,
    all_equal,
    ncycles_sum,
    pairwise_product_sum,
    first_true_index,
    dotproduct,
    recipe_tabulate,
    tabulate_sum,
)

data = [0, 1, 0, 1, 1, 0, 1, 1, 0, 1]
zeros = [0, 0, 0, 0, 0, 7, 0]
same = [3, 3, 3, 3, 3]
a = [1.0, 2.0, 3.0, 4.0, 5.0]
b = [0.5, 0.5, 0.5, 0.5, 0.5]
nums = [1, 2, 3, 4, 5, 6, 7, 8]

_ = quantify(data, 10)
_ = take_sum(5, nums)
_ = all_equal(same, 5)
_ = ncycles_sum(nums, 3, 8)
_ = pairwise_product_sum(nums, 8)
_ = first_true_index(zeros, 7)
_ = dotproduct(a, b, 5)
_ = recipe_tabulate(100, 1)
_ = tabulate_sum(80, 2)
