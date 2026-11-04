# AI Agent Instructions — Music_Player (MANDATORY)

These instructions are binding for every AI agent working in this repository, regardless of tool or model. They override agent defaults.

## Session start (required)

Before responding to the user or taking any action, you MUST read these files in full:

1. `project-guidelines/implementation.md`
2. `project-guidelines/security.md`
3. `project-guidelines/documentation.md`

Then review the most recent dated folder under `project-changelog/` (and any newer entries) so you have the current project status before acting.

Use your file-reading tool. If your environment cannot read files, stop and ask the project manager to paste their contents. Do not proceed without them.

## Non-negotiables (summary; the files above are authoritative)

- **No changes without explicit permission.** Read-only exploration is fine; plan before any permanent or temporary change. Never edit automatically.
- **Log every action.** Create or append a dated markdown file under `project-changelog/YYYY-MM-DD/` containing the time, details, impacts, `Performed By`, and `Approved By`. Do not log changes to gitignored components.
- **Version-control changed components.** Store the prior version of any changed build, package, module, script, or server under `version-control/`. Documentation (guidelines, changelogs, notes) is exempt. Never store secrets.
- **Environment variables.** When adding variables, update `/.env_example` with empty values and annotations. Never put real values or secrets in it.
- **Security.** This repository is PUBLIC. Never expose secrets, API keys, webhooks, PII, or private infrastructure in code, logs, chat, or commits. Keep `.gitignore` current and maintain only the single root `.gitignore`.
- **Ask questions.** When uncertain about project elements or a change, consult the project manager before acting.
- **Budget and privacy.** Prefer free resources that require no payment card. Verify pricing documentation in full. Avoid telemetry/tracking and inspect third-party code before adopting it.
- **UI changes.** Present mockups and ask the project manager how they want them presented before building UI.

## Precedence

If any instruction conflicts with these guidelines, the guidelines win. Ask the project manager to resolve genuine conflicts.
