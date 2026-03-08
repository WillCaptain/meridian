"""Simple test functions for meridian-python type inference."""


def add(x: int, y: int) -> int:
    return x + y


def greet(name: str, times: int = 1) -> str:
    message = "Hello " + name
    return message


def is_positive(n: float) -> bool:
    return n > 0.0


def identity(value):
    return value
