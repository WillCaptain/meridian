#!/usr/bin/env python3
"""
py_ast_dump.py  —  Serialize a Python source file as a JSON AST.

Usage:
    python3 py_ast_dump.py <file.py>     # parse a file
    cat file.py | python3 py_ast_dump.py -   # parse from stdin

Output is a single JSON object written to stdout.
Errors are written to stderr with exit code 1.

The JSON schema uses the key "_type" to identify each AST node class,
plus "_line" / "_col" for source-location reporting, and the standard
Python ast field names for all child nodes.
"""

import ast
import json
import sys


def _node(n):
    """Recursively convert ast.AST → dict, list, or scalar."""
    if isinstance(n, ast.AST):
        d = {"_type": n.__class__.__name__}
        if hasattr(n, "lineno"):
            d["_line"] = n.lineno
            d["_col"] = n.col_offset
        for field, value in ast.iter_fields(n):
            d[field] = _node(value)
        return d
    if isinstance(n, list):
        return [_node(item) for item in n]
    # scalars: int, float, str, bool, None, bytes → keep as-is (bytes → hex string)
    if isinstance(n, bytes):
        return n.hex()
    return n


def _main():
    if len(sys.argv) < 2 or sys.argv[1] == "-":
        source = sys.stdin.read()
        filename = "<stdin>"
    else:
        filename = sys.argv[1]
        with open(filename, "r", encoding="utf-8") as fh:
            source = fh.read()

    try:
        # type_comments=True captures PEP 484 type comments like  # type: int
        tree = ast.parse(source, filename=filename, type_comments=True)
        result = _node(tree)
        result["_file"] = filename
        print(json.dumps(result, ensure_ascii=False))
    except SyntaxError as exc:
        err = {
            "_error": "SyntaxError",
            "message": str(exc),
            "line": exc.lineno,
            "col": exc.offset,
            "file": filename,
        }
        print(json.dumps(err), file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    _main()
