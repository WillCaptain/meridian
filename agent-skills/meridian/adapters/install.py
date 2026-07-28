#!/usr/bin/env python3
"""Install the canonical Meridian skill for Cursor, Codex, or Claude Code."""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


TARGETS = {
    "cursor": Path(".cursor/skills/meridian"),
    "codex": Path(".agents/skills/meridian"),
    "claude-code": Path(".claude/skills/meridian"),
}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=TARGETS)
    parser.add_argument("--project-root", default=".")
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()

    skill_dir = Path(__file__).resolve().parents[1]
    destination = Path(args.project_root).resolve() / TARGETS[args.target]
    if destination.exists():
        if not args.force:
            parser.error(f"{destination} already exists; pass --force to replace it")
        shutil.rmtree(destination)

    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(
        skill_dir,
        destination,
        ignore=shutil.ignore_patterns("adapters", "__pycache__", "*.pyc"),
    )
    print(destination)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
