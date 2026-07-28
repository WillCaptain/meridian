# Meridian production delivery

## Separate the three decisions

Do not force one A/B/C choice. Production delivery has independent axes.

### 1. Compile scope

- **Single module**: one measured module.
- **Import closure** (default): primary facade plus package-local imports.
- **Explicit set**: user-supplied module list.
- **Full package**: every module, only after full gates pass.

`keep_deps` means the primary facade stays dynamically typed for compatibility
while inferred parameter annotations remain on compiled dependencies. It is a
compile preparation policy, not a deployment format.

### 2. Packaging

- **Loose extension sidecar**: `.so` / `.pyd` beside Python modules. Best for
  controlled internal deployment and first production proof.
- **Platform wheel**: Python source plus one or more native extensions. Best for
  repeatable installation and distribution.
- **Container image**: build the wheel/extensions inside the target image and
  deploy that immutable image.

A package compile normally produces multiple extension modules. It does not turn
an arbitrary Python application into one monolithic `.so`.

### 3. Runtime policy

- **Native with Python fallback** (recommended initially).
- **Native required** after parity and operational confidence.
- **Python only** on unsupported ABI/platform.

## Artifact contract

Every production candidate must include:

```text
meridian-artifact/
├── artifact/                 # annotated .py + .so/.pyd outputs
├── type-report/              # inferred source and sites
├── report.json               # machine-readable evidence
├── report.md                 # human summary
├── compile.stdout.log
├── compile.stderr.log
├── test.stdout.log           # when tests ran
└── test.stderr.log
```

The report records source hashes, Python implementation/version/ABI, platform,
compile scope, annotation mode, artifacts, tests, performance status, and claim
boundary.

## Production gates

Apply in order:

1. **Type report**: inference completed; unsupported areas remain explicit.
2. **Compile**: mypyc produced native artifacts for the selected scope.
3. **Correctness**: real application tests pass against the compiled import path.
4. **Performance**: representative workload improves enough to justify shipping.
5. **Compatibility**: build and test every supported CPython ABI/OS/architecture.
6. **Packaging**: wheel or image installs into a clean environment.
7. **Operations**: fallback/rollback, observability, and ownership are defined.

Compile success alone is never deployment readiness.

## Recommended adoption path

1. Run report-only mode on the application.
2. Identify a real hot facade from profiling or known workload.
3. Compile its import closure with `keep_deps`.
4. Run parity tests and benchmark.
5. Deploy a sidecar or internal wheel with Python fallback.
6. Expand compile scope only when additional modules pass the same gates.

This path gives developers a single skill workflow without requiring them to
understand mypyc packaging before the first useful report.
