# Version Control Folder Layout Added to Guidelines

- **Time:** 2026-10-29 16:55:48 UTC
- **Change:** Added one line to the "Version control" section of `project-guidelines/implementation.md` defining the folder layout for stored prior versions.

## Details

Added line (placed after the list of covered components):

```
* Store prior versions using the layout "/version-control/<component>/<YYYY-MM-DD_HHMM>/<file>"
```

No other part of the guideline was edited.

## Impacts

- Prior versions of changed components now have one consistent location, for example `/version-control/opus-tags/2026-11-02_1430/OpusTags.kt`.
- Guidelines are documentation and exempt from version control, so no prior copy of `implementation.md` was stored.
- No application code, dependencies, environment variables, or services were changed.
- No secrets, API keys, or PII were introduced.

## Performed By

Claude Code (model: Claude Opus 5.5)

## Approved By

Project Manager (User)
