#!/usr/bin/env python3
"""Strip annotations for mypyc-friendly compile of large upstream modules.

Reads source from stdin; writes to stdout.

Modes (argv[1]):
  all     — strip every annotation (params, returns, AnnAssign)
  returns — strip returns + AnnAssign only (keep param annotations)
"""
from __future__ import annotations

import ast
import sys


def strip(src: str, mode: str) -> str:
    class T(ast.NodeTransformer):
        def visit_FunctionDef(self, node: ast.FunctionDef) -> ast.FunctionDef:
            self.generic_visit(node)
            node.returns = None
            if mode == "all":
                for a in (
                    list(node.args.args)
                    + list(node.args.kwonlyargs)
                    + list(node.args.posonlyargs)
                ):
                    a.annotation = None
                if node.args.vararg is not None:
                    node.args.vararg.annotation = None
                if node.args.kwarg is not None:
                    node.args.kwarg.annotation = None
            return node

        def visit_AnnAssign(self, node: ast.AnnAssign):
            self.generic_visit(node)
            if node.value is None:
                return None
            return ast.copy_location(
                ast.Assign(targets=[node.target], value=node.value), node
            )

    tree = T().visit(ast.parse(src))
    ast.fix_missing_locations(tree)
    return ast.unparse(tree) + "\n"


def main() -> None:
    mode = sys.argv[1] if len(sys.argv) > 1 else "all"
    if mode not in ("all", "returns"):
        raise SystemExit(f"unknown mode: {mode}")
    sys.stdout.write(strip(sys.stdin.read(), mode))


if __name__ == "__main__":
    main()
