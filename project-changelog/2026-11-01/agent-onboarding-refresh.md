# Agent onboarding refresh: rules + logs for all providers

- **Time:** 2026-11-01 19:35:42 UTC
- **Change:** Updated the repository-wide AI agent instruction files so every supported agent (Cline, Continue, Copilot, Cursor, Devin, Roo, Windsurf, Warp, Aider, OpenCode, Claude Code, Gemini) now points at the full rule set (`AGENTS.md`, `implementation.md`, `security.md`, `documentation.md`) and reviews the latest `project-changelog/` entries at session start. Added the legacy `.cursorrules` file for older Cursor installs. No application code, dependencies, env vars or services were added.

## Details

Updated files:
- `AGENTS.md` — Session start now requires reading `project-guidelines/documentation.md` and reviewing the most recent dated folder under `project-changelog/`.
- `.clinerules`, `.continuerules`, `.github/copilot-instructions.md`, `.cursor/rules/project-guidelines.mdc`, `.devin/instructions.md`, `.roo/rules/project-guidelines.md`, `.windsurf/rules/project-guidelines.md`, `.windsurfrules`, `WARP.md`, `CONVENTIONS.md` — pointer sentence now references `documentation.md` and the latest changelog entries.
- `opencode.json` — instructions now include `AGENTS.md` and `documentation.md`.
- `.aider.conf.yml` — read list now includes `documentation.md`.
- `.cursorrules` — new legacy Cursor pointer matching the above.

`CLAUDE.md` and `GEMINI.md` already import `AGENTS.md`, so they inherit the expanded requirements.

## Impacts
- Agents no longer need to be reprompted to read the project rules or the changelog logs; the requirements are encoded in their provider-specific config files.
- No secrets, API keys, PII or network access were introduced.
- `.gitignore` unchanged (these are documentation/config files, intended to be public).
- No version-control copy stored: these are documentation/config files, which the guidelines exempt.

## Performed By
Cline (AI agent)

## Approved By
Project Manager (User): requested adding agent settings and other providers.