# Meridian agent skill

The canonical, host-neutral skill is:

```text
agent-skills/meridian/
```

It implements a report-first Meridian workflow for Python type evidence,
selective mypyc compilation, correctness/performance gates, and production
artifact handoff.

Install into a project:

```bash
# Cursor
python3 agent-skills/meridian/adapters/install.py cursor --project-root /path/to/project

# OpenAI Codex
python3 agent-skills/meridian/adapters/install.py codex --project-root /path/to/project

# Claude Code
python3 agent-skills/meridian/adapters/install.py claude-code --project-root /path/to/project
```

The adapters copy one canonical skill rather than maintaining three divergent
prompts. See each file under `meridian/adapters/` for host-specific discovery
and invocation notes.
