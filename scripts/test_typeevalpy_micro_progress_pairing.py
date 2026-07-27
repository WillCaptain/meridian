#!/usr/bin/env python3
"""Unit tests for TypeEvalPy site pairing modes (no Meridian subprocess)."""

from __future__ import annotations

import unittest

from importlib.machinery import SourceFileLoader
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "run-typeevalpy-micro-progress.py"
mod = SourceFileLoader("typeevalpy_micro_progress", str(SCRIPT)).load_module()


def _lv(file: str, line: int, col: int, variable: str, types: list[str]) -> dict:
    return {
        "file": file,
        "line_number": line,
        "col_offset": col,
        "variable": variable,
        "type": types,
    }


def _fact(
    *,
    file: str,
    line: int,
    col: int,
    kind: str,
    symbol: str,
    expected: str,
) -> dict:
    return {
        "fact_id": f"{file}:{line}:{col}/{kind}/{symbol}",
        "file": file,
        "line": str(line),
        "column": str(col),
        "oracle_kind": kind,
        "symbol": symbol,
        "expected_types": expected,
        "category": "dicts",
        "template": "t",
    }


class SitePairingModesTest(unittest.TestCase):
    def test_strict_requires_exact_key(self):
        # Same line+symbol, different column — soft locators recover; strict does not.
        site = _lv("main.py", 10, 5, "d", ["dict"])
        idx = mod.index_sites([site])
        fact = _fact(
            file="main.py",
            line=10,
            col=4,
            kind="LV",
            symbol="d",
            expected="dict",
        )
        self.assertIsNone(mod.find_site_strict(fact, idx))
        soft = mod.find_site_compat(fact, idx, use_expected_types=False)
        self.assertIs(soft, site)

    def test_compat_does_not_use_expected_types(self):
        # Two co-located sites; expected matches only the element.
        bare = _lv("main.py", 3, 0, "d", ["dict"])
        elem = _lv("main.py", 3, 0, "d['a']", ["str"])
        idx = mod.index_sites([bare, elem])
        fact = _fact(
            file="main.py",
            line=3,
            col=0,
            kind="LV",
            symbol="missing",
            expected="str",
        )
        legacy = mod.find_site_compat(fact, idx, use_expected_types=True)
        compat = mod.find_site_compat(fact, idx, use_expected_types=False)
        self.assertEqual(mod.types_of(legacy), {"str"})
        # Without expected types, specificity heuristic prefers subscript over bare.
        self.assertEqual(mod.types_of(compat), {"str"})
        # And when only bare matches expected (legacy), expected decides.
        fact_dict = _fact(
            file="main.py",
            line=3,
            col=0,
            kind="LV",
            symbol="missing",
            expected="dict",
        )
        legacy_dict = mod.find_site_compat(fact_dict, idx, use_expected_types=True)
        compat_dict = mod.find_site_compat(fact_dict, idx, use_expected_types=False)
        self.assertEqual(mod.types_of(legacy_dict), {"dict"})
        # Compat must not peek at expected=dict; specificity still picks subscript.
        self.assertEqual(mod.types_of(compat_dict), {"str"})

    def test_strict_exact_hit(self):
        site = _lv("main.py", 7, 2, "x", ["int"])
        idx = mod.index_sites([site])
        fact = _fact(
            file="main.py",
            line=7,
            col=2,
            kind="LV",
            symbol="x",
            expected="int",
        )
        self.assertIs(mod.find_site_strict(fact, idx), site)


if __name__ == "__main__":
    unittest.main()
