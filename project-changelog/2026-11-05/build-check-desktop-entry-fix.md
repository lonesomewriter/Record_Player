# Fix: build.sh's menu-entry check sometimes picked a folder

**Time:** 2026-11-05, ~21:30 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager (part of the requested desktop build; the check was failing it).

## Cause
- The check searched the extracted `.deb` for anything named `*.desktop` under a `lib/` folder.
- The bundled Java runtime contains a licence folder named `runtime/legal/java.desktop`. When the file system listed it first, the check took the folder, found it wasn't a file, and reported "no .desktop file in the package", although the menu entry was there.
- It depended on file order, so it passed in some builds and failed in others. The package itself was fine.

## Fix
- `scripts/build.sh` now searches for files only (`-type f`) outside the bundled runtime.
- Prior version: `version-control/scripts/2026-11-05_2128/build.sh`.

## Build
`scripts/build.sh dev --desktop` passed all checks, including the fixed menu-entry check: `project-builds/dev-builds/RecordPlayer-linux-0.1.0_2026-11-05_2128/`.
