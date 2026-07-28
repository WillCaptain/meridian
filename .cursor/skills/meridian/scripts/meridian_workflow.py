#!/usr/bin/env python3
"""Portable Meridian check → report → compile → test workflow."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resolve_meridian(explicit: str | None) -> Path:
    candidates: list[Path] = []
    if explicit:
        candidates.append(Path(explicit).expanduser())
    if os.environ.get("MERIDIAN_BIN"):
        candidates.append(Path(os.environ["MERIDIAN_BIN"]).expanduser())
    on_path = shutil.which("meridian")
    if on_path:
        candidates.append(Path(on_path))

    here = Path.cwd().resolve()
    for parent in (here, *here.parents):
        candidates.append(parent / "bin" / "meridian")
        candidates.append(parent / "meridian" / "bin" / "meridian")

    for candidate in candidates:
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return candidate.resolve()
    raise RuntimeError(
        "Meridian executable not found. Set MERIDIAN_BIN or pass --meridian-bin."
    )


def run_command(
    command: list[str],
    *,
    cwd: Path | None = None,
    stdout_path: Path | None = None,
    stderr_path: Path | None = None,
) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        cwd=cwd,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if stdout_path:
        stdout_path.write_text(result.stdout, encoding="utf-8")
    if stderr_path:
        stderr_path.write_text(result.stderr, encoding="utf-8")
    return result


def count_sites(value: Any) -> int:
    if isinstance(value, list):
        return len(value)
    if isinstance(value, dict):
        for key in ("sites", "results", "items"):
            if isinstance(value.get(key), list):
                return len(value[key])
    return 0


def source_inventory(source: Path | None, package_dir: Path | None) -> list[dict[str, str]]:
    if source:
        paths = [source]
    else:
        assert package_dir is not None
        paths = sorted(package_dir.glob("*.py"))
    return [
        {"path": str(path.resolve()), "sha256": sha256_file(path)}
        for path in paths
        if path.is_file()
    ]


def runtime_metadata() -> dict[str, str]:
    return {
        "python_implementation": platform.python_implementation(),
        "python_version": platform.python_version(),
        "python_abi": getattr(sys.implementation, "cache_tag", "unknown"),
        "platform": platform.platform(),
        "machine": platform.machine(),
    }


def write_report(report_dir: Path, payload: dict[str, Any]) -> None:
    report_dir.mkdir(parents=True, exist_ok=True)
    (report_dir / "report.json").write_text(
        json.dumps(payload, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    gates = payload.get("gates", {})
    artifacts = payload.get("artifacts", [])
    compile_scope = payload.get("compile_scope", {})
    lines = [
        "# Meridian report",
        "",
        f"- Status: **{payload.get('status', 'unknown')}**",
        f"- Generated: `{payload.get('generated_at', '')}`",
        f"- Mode: `{payload.get('mode', '')}`",
        f"- Type sites: **{payload.get('type_sites', 'not measured')}**",
        f"- Compile success: **{gates.get('compile', 'not run')}**",
        f"- Correctness test: **{gates.get('correctness', 'not run')}**",
        f"- Performance: **{gates.get('performance', 'not measured')}**",
        f"- Annotation mode: `{compile_scope.get('annotation_mode', 'n/a')}`",
        f"- Compiled modules: `{', '.join(compile_scope.get('modules', [])) or 'n/a'}`",
        "",
        "## Native artifacts",
        "",
    ]
    if artifacts:
        lines.extend(f"- `{item['path']}` ({item['sha256']})" for item in artifacts)
    else:
        lines.append("- none")
    lines.extend(
        [
            "",
            "## Production boundary",
            "",
            f"- Packaging: {payload.get('packaging', 'not decided')}",
            f"- Fallback: {payload.get('fallback_policy', 'not decided')}",
            f"- Claim: {payload.get('claim_boundary', '')}",
            "",
            "See `report.json` and command logs for machine-readable evidence.",
        ]
    )
    (report_dir / "report.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def check_source(
    meridian: Path, source: Path, report_dir: Path
) -> tuple[dict[str, Any], int]:
    report_dir.mkdir(parents=True, exist_ok=True)
    annotated = report_dir / "annotated.py"
    sites = report_dir / "type-sites.json"

    infer_cmd = [str(meridian), "infer", str(source), "-o", str(annotated)]
    infer = run_command(
        infer_cmd,
        stdout_path=report_dir / "infer.stdout.log",
        stderr_path=report_dir / "infer.stderr.log",
    )
    sites_cmd = [str(meridian), "sites", str(source), "-o", str(sites)]
    site_run = run_command(
        sites_cmd,
        stdout_path=report_dir / "sites.stdout.log",
        stderr_path=report_dir / "sites.stderr.log",
    )

    site_count = 0
    if sites.is_file():
        try:
            site_count = count_sites(json.loads(sites.read_text(encoding="utf-8")))
        except json.JSONDecodeError:
            pass

    ok = infer.returncode == 0 and site_run.returncode == 0
    payload: dict[str, Any] = {
        "schema_version": 1,
        "generated_at": utc_now(),
        "status": "passed" if ok else "failed",
        "mode": "check",
        "meridian_bin": str(meridian),
        "inputs": source_inventory(source, None),
        "runtime": runtime_metadata(),
        "type_sites": site_count,
        "commands": {
            "infer": infer_cmd,
            "sites": sites_cmd,
        },
        "gates": {
            "type_check": "passed" if ok else "failed",
            "compile": "not run",
            "correctness": "not run",
            "performance": "not measured",
        },
        "artifacts": [],
        "packaging": "not decided",
        "fallback_policy": "not decided",
        "claim_boundary": (
            "Type/site report only; site count is not a full-coverage or correctness claim."
        ),
    }
    write_report(report_dir, payload)
    return payload, 0 if ok else 1


def native_artifacts(output_dir: Path) -> list[dict[str, str]]:
    paths: list[Path] = []
    for pattern in ("*.so", "*.pyd", "*.dylib"):
        # mypyc also leaves duplicate staging copies under build/. Production
        # handoff needs only the top-level loadable set (including __mypyc).
        paths.extend(output_dir.glob(pattern))
    return [
        {"path": str(path.resolve()), "sha256": sha256_file(path)}
        for path in sorted(set(paths))
        if path.is_file()
    ]


def parse_compiled_modules(stderr: str) -> list[str]:
    for line in stderr.splitlines():
        if line.startswith("Compile:"):
            return [part.strip() for part in line.split(":", 1)[1].split(",") if part.strip()]
    return []


def parse_benchmark(stdout: str) -> dict[str, Any] | None:
    for line in reversed(stdout.splitlines()):
        try:
            value = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and isinstance(value.get("rows"), list):
            return value
    return None


def run_test(command: str, cwd: Path, report_dir: Path) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        command,
        cwd=cwd,
        shell=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    (report_dir / "test.stdout.log").write_text(result.stdout, encoding="utf-8")
    (report_dir / "test.stderr.log").write_text(result.stderr, encoding="utf-8")
    return result


def compile_target(args: argparse.Namespace) -> int:
    meridian = resolve_meridian(args.meridian_bin)
    report_dir = Path(args.report_dir).resolve()
    output_dir = report_dir / "artifact"
    type_dir = report_dir / "type-report"
    report_dir.mkdir(parents=True, exist_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)

    source = Path(args.source).resolve() if args.source else None
    package_dir = Path(args.package_dir).resolve() if args.package_dir else None
    primary_source: Path
    if source:
        primary_source = source
    else:
        assert package_dir is not None and args.primary
        primary_source = package_dir / f"{args.primary}.py"
        if not primary_source.is_file():
            raise RuntimeError(f"Primary source not found: {primary_source}")

    type_payload, type_code = check_source(meridian, primary_source, type_dir)
    if type_code != 0:
        type_payload["mode"] = "compile"
        type_payload["status"] = "blocked"
        type_payload["claim_boundary"] = "Compilation blocked because type reporting failed."
        write_report(report_dir, type_payload)
        return 1

    if source:
        command = [str(meridian), "compile", str(source), "-o", str(output_dir)]
    else:
        assert package_dir is not None
        command = [
            str(meridian),
            "compile",
            "--pkg",
            str(package_dir),
            "--primary",
            args.primary,
            "--annotation-mode",
            args.annotation_mode,
            "-o",
            str(output_dir),
        ]
        if args.compile_modules:
            command.extend(["--compile-modules", args.compile_modules])
        else:
            command.append("--compile-imports")

    if args.calls:
        command.extend(["--calls", str(Path(args.calls).resolve())])
    elif args.calls_inline:
        command.extend(["--calls-inline", args.calls_inline])
    if args.annotate_all:
        command.append("--annotate-all")
    if args.bench:
        if package_dir:
            raise RuntimeError("--bench is currently supported for single-module compile only")
        command.extend(["--bench", args.bench])

    compiled = run_command(
        command,
        stdout_path=report_dir / "compile.stdout.log",
        stderr_path=report_dir / "compile.stderr.log",
    )
    artifacts = native_artifacts(output_dir)
    benchmark = parse_benchmark(compiled.stdout) if args.bench else None

    test_result: subprocess.CompletedProcess[str] | None = None
    if compiled.returncode == 0 and args.test_command:
        test_result = run_test(args.test_command, Path(args.test_cwd).resolve(), report_dir)

    compile_ok = compiled.returncode == 0 and bool(artifacts)
    benchmark_correct = bool(benchmark) and bool(benchmark.get("ok")) and all(
        bool(row.get("correct")) for row in benchmark.get("rows", [])
    )
    if args.test_command:
        correctness = (
            "passed" if test_result and test_result.returncode == 0 else "failed"
        )
        if args.bench and not benchmark_correct:
            correctness = "failed"
    elif args.bench:
        correctness = "passed" if benchmark_correct else "failed"
    else:
        correctness = "not run"

    speedups = [
        float(row["speedup_vs_native"])
        for row in (benchmark or {}).get("rows", [])
        if isinstance(row, dict) and isinstance(row.get("speedup_vs_native"), (int, float))
    ]
    performance: str | float = (
        round(sum(speedups) / len(speedups), 3) if speedups else "not measured"
    )
    overall = compile_ok and correctness != "failed"
    modules = parse_compiled_modules(compiled.stderr)
    if source and not modules:
        modules = [source.stem]

    payload = {
        "schema_version": 1,
        "generated_at": utc_now(),
        "status": "passed" if overall else "failed",
        "mode": "compile",
        "meridian_bin": str(meridian),
        "inputs": source_inventory(source, package_dir),
        "runtime": runtime_metadata(),
        "type_sites": type_payload.get("type_sites", 0),
        "commands": {
            "compile": command,
            "test": args.test_command or None,
        },
        "compile_scope": {
            "strategy": (
                "single module"
                if source
                else "explicit modules"
                if args.compile_modules
                else "primary import closure"
            ),
            "primary": source.stem if source else args.primary,
            "annotation_mode": "single-module policy" if source else args.annotation_mode,
            "modules": modules,
        },
        "gates": {
            "type_check": "passed",
            "compile": "passed" if compile_ok else "failed",
            "correctness": correctness,
            "performance": performance,
        },
        "benchmark": benchmark,
        "artifacts": artifacts,
        "packaging": "loose native extension(s); wheel not built",
        "fallback_policy": "not decided",
        "claim_boundary": (
            "Build artifact only. Production readiness requires target-ABI testing, "
            "a packaging decision, correctness evidence, and rollback/fallback policy."
        ),
    }
    write_report(report_dir, payload)
    return 0 if overall else 1


def doctor(args: argparse.Namespace) -> int:
    try:
        meridian = resolve_meridian(args.meridian_bin)
    except RuntimeError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    result = run_command([str(meridian), "version"])
    print(
        json.dumps(
            {
                "ok": result.returncode == 0,
                "meridian_bin": str(meridian),
                "version": result.stdout.strip(),
                "runtime": runtime_metadata(),
            },
            indent=2,
        )
    )
    return 0 if result.returncode == 0 else 1


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument("--meridian-bin", help="Meridian executable")
    sub = root.add_subparsers(dest="command", required=True)

    sub.add_parser("doctor", help="Verify Meridian and runtime").set_defaults(handler=doctor)

    check = sub.add_parser("check", help="Create type/site report")
    check.add_argument("--source", required=True)
    check.add_argument("--report-dir", required=True)
    check.set_defaults(
        handler=lambda args: check_source(
            resolve_meridian(args.meridian_bin),
            Path(args.source).resolve(),
            Path(args.report_dir).resolve(),
        )[1]
    )

    compile_cmd = sub.add_parser("compile", help="Report, compile, and optionally test")
    target = compile_cmd.add_mutually_exclusive_group(required=True)
    target.add_argument("--source")
    target.add_argument("--package-dir")
    compile_cmd.add_argument("--primary")
    compile_cmd.add_argument("--calls")
    compile_cmd.add_argument("--calls-inline")
    compile_cmd.add_argument(
        "--annotation-mode",
        default="keep_deps",
        choices=("keep_deps", "strip_deps", "keep", "strip_all"),
    )
    compile_cmd.add_argument("--compile-imports", action="store_true")
    compile_cmd.add_argument("--compile-modules")
    compile_cmd.add_argument("--annotate-all", action="store_true")
    compile_cmd.add_argument("--bench")
    compile_cmd.add_argument("--test-command")
    compile_cmd.add_argument("--test-cwd", default=".")
    compile_cmd.add_argument("--report-dir", required=True)
    compile_cmd.set_defaults(handler=compile_target)
    return root


def validate_args(args: argparse.Namespace) -> None:
    if args.command != "compile":
        return
    if args.package_dir and not args.primary:
        raise RuntimeError("--primary is required with --package-dir")
    if args.source and (args.primary or args.compile_modules or args.compile_imports):
        raise RuntimeError("Package-only options used with --source")
    if args.compile_modules and args.compile_imports:
        raise RuntimeError("Use only one of --compile-modules or --compile-imports")
    if args.calls and args.calls_inline:
        raise RuntimeError("Use only one of --calls or --calls-inline")


def main() -> int:
    args = parser().parse_args()
    try:
        validate_args(args)
        return int(args.handler(args))
    except (OSError, RuntimeError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
