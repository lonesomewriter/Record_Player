# Agent Onboarding Enforcement

- **Time:** 2026-10-29 12:00:12 UTC
- **Change:** Added repository-wide AI agent instruction files so any agent that supports conventional instruction discovery loads and follows `project-guidelines/` on initialization.

## Details

Created the following files:

- `AGENTS.md` — canonical entrypoint; requires reading `project-guidelines/implementation.md` and `project-guidelines/security.md` in full at session start, summarizes the non-negotiables, and establishes precedence.
- `opencode.json` — registers both guideline files under `instructions`.
- `CLAUDE.md` — imports `AGENTS.md` (`@AGENTS.md`).
- `GEMINI.md` — imports `AGENTS.md` (`@./AGENTS.md`).
- `.github/copilot-instructions.md` — pointer to `AGENTS.md` and the guidelines.
- `.cursor/rules/project-guidelines.mdc` — always-apply Cursor rule pointing to the guidelines.
- `.windsurf/rules/project-guidelines.md` and `.windsurfrules` — Windsurf rules pointing to the guidelines.
- `.clinerules` — Cline pointer.
- `.roo/rules/project-guidelines.md` — Roo Code pointer.
- `.continuerules` — Continue pointer.
- `WARP.md` — Warp pointer.
- `.aider.conf.yml` and `CONVENTIONS.md` — Aider configuration/pointer.
- `.devin/instructions.md` — Devin pointer.

Also tracked the previously untracked `project-guidelines/` directory and this changelog entry so the guidelines are available to other agents and clones.

## Impacts

- Every supported AI agent now receives binding project instructions at initialization.
- No application code, dependencies, environment variables, or services were added.
- No secrets, API keys, or PII were introduced.
- `.gitignore` required no change; the new files contain no sensitive data and are intended to be public.
- Agents that do not support any of these conventional files are not covered (best-effort guarantee).

## Performed By

opencode (model: big-pickle)

## Approved By

Project Manager (User)
