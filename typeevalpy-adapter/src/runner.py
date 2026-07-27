#!/usr/bin/env python3
"""TypeEvalPy target_tools/meridian runner (pysonar2-shaped).

Invokes Meridian CLI `sites` for each .py file and writes `*_result.json`.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import subprocess
import sys
from pathlib import Path

import utils

logger = logging.getLogger("runner")
logger.setLevel(logging.DEBUG)
_handler = logging.StreamHandler(sys.stdout)
_handler.setFormatter(
    logging.Formatter("%(asctime)s - %(name)s - %(levelname)s - %(message)s")
)
logger.addHandler(_handler)


def list_python_files(folder_path: str | Path) -> list[Path]:
    return sorted(Path(folder_path).rglob("*.py"))


def meridian_bin() -> str:
    return os.environ.get("MERIDIAN_BIN", "meridian")


def infer_sites(py_file: Path) -> list:
    cmd = [meridian_bin(), "sites", str(py_file)]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=180)
    if proc.returncode != 0:
        raise RuntimeError(proc.stderr or proc.stdout or f"exit {proc.returncode}")
    data = json.loads(proc.stdout) if proc.stdout.strip() else []
    # Force basename — harness GT uses e.g. main.py
    for row in data:
        row["file"] = py_file.name
    return data


def main_runner(args) -> None:
    python_files = list_python_files(args.bechmark_path)
    errors = 0
    for i, file in enumerate(python_files, 1):
        try:
            logger.info("%s", file)
            sites = infer_sites(file)
            out = Path(str(file).replace(".py", "_result.json"))
            out.write_text(json.dumps(sites, indent=4), encoding="utf-8")
        except Exception as e:
            logger.info("error for %s: %s", file, e)
            errors += 1
        logger.info("Progress: %s/%s", i, len(python_files))
    logger.info("Runner finished with errors:%s", errors)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--bechmark_path",
        help="TypeEvalPy micro-benchmark root (typo kept for harness compat)",
        default="/tmp/micro-benchmark",
    )
    args = parser.parse_args()
    if utils.is_running_in_docker():
        logger.info("Python is running inside a Docker container")
    main_runner(args)
