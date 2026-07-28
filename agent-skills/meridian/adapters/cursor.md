# Cursor adapter

Install the canonical skill into a project:

```bash
python3 agent-skills/meridian/adapters/install.py cursor \
  --project-root /path/to/project
```

This copies it to `.cursor/skills/meridian/`. Cursor also understands
`.agents/skills`, but the explicit Cursor location keeps installation intent
clear. Do not install both Cursor and Codex copies in the same repository when
using Cursor, because Cursor may discover both.

Invoke with `/meridian` or ask to type-check, optimize, compile, test, package,
or prepare a Python production artifact with Meridian.
