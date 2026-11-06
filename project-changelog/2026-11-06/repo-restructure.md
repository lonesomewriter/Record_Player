# Repo restructure for the new Folder Structure rule

**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager. Their answers: keep "Project manager" in logs; move the whole Gradle project; version catalog in dependencies/; mockups private. Then: "Plan is good to go, you can commit and ignore the rest of the mockups". Plan: `project-notes/restructure-plan.md`.

## 1. Before the move (2026-11-06 ~13:55 UTC)
- **implementation.md line 6:** back to "Project manager" for Performed By / Approved By, plus "Never write the user's name, username or email". The project manager's update had changed it to "write down your name", which conflicted with security.md → Anonymity; they chose the old wording. The rest of their update (the Folder Structure section, the removed version-control layout line, the wording fixes) is kept as they wrote it.
- **`.gitignore`:** mockups and their backups are private (`/project/mockups/`, `/version-control/project/mockups/`, `/version-control/project-notes/mockups/`).
  - Mockup backups already committed earlier stay in the public history.
  - The new, uncommitted ones from 2026-11-05/06 are not committed.
- **Commit of the finished work, not pushed:**
  - still accent shine, backlight, Battery Saver, Shine brightness
  - desktop transitions and the build-script fix
  - the changelogs, backups and build logs
  - A privacy scan found no username, email or phone model in the committed files.
