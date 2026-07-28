# Codex adapter

Install the canonical skill into a project:

```bash
python3 agent-skills/meridian/adapters/install.py codex \
  --project-root /path/to/project
```

This copies it to `.agents/skills/meridian/`, Codex's repository skill
location. Restart/reload Codex if the current session was started before
installation.

Invoke `$meridian` explicitly, or ask Codex to type-check, optimize, compile,
test, package, or prepare a Python production artifact with Meridian.
