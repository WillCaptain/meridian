# Claude Code adapter

Install the canonical skill into a project:

```bash
python3 agent-skills/meridian/adapters/install.py claude-code \
  --project-root /path/to/project
```

This copies it to `.claude/skills/meridian/`. Claude Code discovers the
`SKILL.md` metadata and loads the workflow on demand.

Invoke `/meridian` explicitly, or ask Claude Code to type-check, optimize,
compile, test, package, or prepare a Python production artifact with Meridian.
