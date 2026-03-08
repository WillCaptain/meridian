"""Class with type-annotated fields for meridian-python type inference."""


class Point:
    x: float
    y: float

    def distance(self, other: object) -> float:
        dx = self.x - other.x
        dy = self.y - other.y
        return dx * dx + dy * dy


class User:
    name: str
    age: int
    active: bool

    def display_name(self) -> str:
        return self.name
