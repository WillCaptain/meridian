# Package facade — re-exports for usage/bench (like more_itertools/__init__.py).

from mi_recipes import (
    quantify,
    take_sum,
    all_equal,
    ncycles_sum,
    pairwise_product_sum,
    first_true_index,
    dotproduct,
    recipe_tabulate,
)
from mi_numeric import tabulate_sum, mul_acc

__all__ = [
    "quantify",
    "take_sum",
    "all_equal",
    "ncycles_sum",
    "pairwise_product_sum",
    "first_true_index",
    "dotproduct",
    "recipe_tabulate",
    "tabulate_sum",
    "mul_acc",
]
