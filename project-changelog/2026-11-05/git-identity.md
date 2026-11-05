# Commit name and email for this repo

**Time:** 2026-11-05 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: chose both values when asked, as required by `project-guidelines/security.md` → Anonymity.

## Details
- The repo-local Git settings (`.git/config`, never pushed) are now:
  - `user.name` = `Record Player maintainer`
  - `user.email` = `recordspinner@users.noreply.invalid`
- `.invalid` is a domain reserved so that it can never belong to anyone, so commits can't be credited to another GitHub account.
- These override the Codespace's system-wide settings for this repo only.

## Impacts
- New commits show the neutral name and email.
- Older commits are unchanged until the planned history rewrite. That is still to come, and it stops for the project manager's go-ahead before any force-push.
- No files in the repo changed.
- Also: `project-guidelines/implementation.md` line 6 now says to write "Project manager" for Performed By / Approved By, and never the user's name, username or email (project manager's OK, 2026-11-05).
