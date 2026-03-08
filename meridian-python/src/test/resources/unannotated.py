"""Functions with no type annotations — meridian should infer and insert them."""


def multiply(x, y):
    return x * y


def greet(name):
    return "Hello " + name


def double(n):
    result = n + n
    return result
