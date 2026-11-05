# Guideline check before committing and pushing

**Time:** 2026-11-05 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: "commit and push everything, but make sure the repo abides by the guidelines outlined first"

## Checked
- **implementation.md:**
  - Every change since the last push has a changelog with Performed By / Approved By ("Project manager").
  - Every changed component's prior version is under `version-control/`.
  - No new environment variables, so `.env_example` needs no change.
- **security.md:**
  - All 107 changed or new files were scanned. None contain the project manager's name, username, any of their emails, the phone model, keys, tokens or private keys. The only home paths are the made-up test paths `/home/me` and `/home/you`.
  - Commits use the neutral Git identity.
- **documentation.md:** `project-notes/documents/feature_documentation.md` and `programming_documentation.md` now cover View Location, Open with, Convert to Audio, the desktop Inbox and the licence. These files are gitignored, so they are not pushed.

## Fixed
- **`.gitignore`:** added `*.p12`, `*.pfx` and `*.key` (certificate and signing-key formats) next to the keystore entries. `*.pem` was already there. No tracked file matches them. `.gitignore` is configuration, so there's no backup.
- **`scripts/build.sh`:** two comments pointed at `project-guidelines/build_packaging.md`, which the project manager renamed to `linux_packaging.md`. Prior version: `version-control/scripts/2026-11-05_1346/`.

## Included as the project manager made them
- `project-guidelines/security.md` (new Anonymity section)
- `build_packaging.md` → `linux_packaging.md` (rename)
