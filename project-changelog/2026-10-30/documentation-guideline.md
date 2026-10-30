# Documentation guideline added to the repo

- **Time:** 2026-10-30 18:42 UTC
- **Change:** `project-guidelines/documentation.md` (written by the PM) is now tracked in git. It asks for two plain-language documents:
  - `project-notes/documents/programming_documentation.md`: how the code works, why the language was chosen, and what any keys or secrets are for (there are none);
  - `project-notes/documents/feature_documentation.md`: the record of the app's features.
- **Status:** both documents were written on 2026-10-30, covering the app through Part 4. They live in `project-notes/`, which is gitignored, so they stay on the PM's machine and aren't in the repo.

## Impacts

- Documentation only: no app code, dependencies, environment variables or `.gitignore` change. No version-control copy is needed for guidelines.
- `AGENTS.md` still lists only `implementation.md` and `security.md` as required reading at session start; it was not changed.

## Performed By

Claude Code (model: Claude Opus 5.5)

## Approved By

Project Manager (User): "Include the "documentation" markdown file".
