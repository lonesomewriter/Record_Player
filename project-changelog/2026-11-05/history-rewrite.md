# Commit history rewrite: removing the project manager's name and email

**Time:** 2026-11-05, ~14:00 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: "let's go with steps 1-4". The force-push to GitHub waits for a separate go-ahead.

## Why
`project-guidelines/security.md` → Anonymity: the user's email and username must never be exposed. The repo is still private, so rewriting now means the old values are never public.

## What was done (in a separate fresh clone from GitHub; the working folder was untouched)
- Rewrote all 28 commits on the 5 pushed branches (main, ui, library-scanning, playback, project-setup) with Git's built-in `filter-branch`. No new tool was installed.
  - Author and committer are now `Record Player maintainer <recordspinner@users.noreply.invalid>`.
  - One commit made through GitHub's website keeps `GitHub <noreply@github.com>` as its committer (no personal details).
  - Dates, messages and every other file are unchanged.
- In the 5 pushed changelogs that had it, "Approved By: <name> (project manager)" now reads "Approved By: Project manager". These were the only file changes (10 lines), and they now match the working copy.

## Verified
- The old name and email appear in no commit header, message or file on any rewritten branch.
- Commit count (28) and dates are unchanged.
- Each branch tip differs from the original only in those 5 changelog files (main, ui); the other three branches are identical in content.
- New tip of main and ui: `adb193e` (was `e6add80`).

## Pushed and synced
- **Force-push:** the agent's attempt was blocked by Claude Code's safety check, so the project manager ran it from a prepared script (`--force-with-lease` against the verified old commits).
- **Confirmed after fetching:** all 5 branches on GitHub equal the rewritten ones, and the old identity appears 0 times in their history.
- **This Codespace:** its branches now point at the new commits (main, library-scanning, playback, project-setup moved; `ui` reset with `--mixed`, so files were untouched).
  - Nothing was staged beforehand, so nothing was lost.
  - All uncommitted work was kept. The 5 rewritten changelogs no longer show as changed, because they match the new commits.
- **Kept on purpose (project manager's choice):** the local editor checkpoint refs (Cline and agent checkpoints) still hold the old commits. They are local only and never pushed.

Old commit IDs mentioned in earlier changelogs (e.g. `e6add80`) refer to the pre-rewrite history.
