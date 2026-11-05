# Fix: "View file location" on Android

**Time:** 2026-11-04, 21:35–21:40 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: "go ahead with this fix and build the new dev APK"

## Problem
- **Project manager's report:** on Android, choosing ⋮ → View file location opened the file manager (Samsung My Files), which said "Unable to open file".
- **Cause:** `viewFileLocation` sent the song's folder, a document URI from the folder picker, as type `*/*` ("any file"). The file manager tried to open the folder as a file. When no app accepted it, nothing happened and nothing was said.

## Change
`shared/src/androidMain/.../ui/Platform.android.kt` (prior version in `version-control/shared/2026-11-04_2135/`):
- The folder is now sent as a **folder** (`DocumentsContract.Document.MIME_TYPE_DIR`), using its plain document address. The Files app reads it with its own access, so no permission grant is needed.
- It tries these in order and uses the first that starts:
  1. Android's own Files app (`com.google.android.documentsui`, then `com.android.documentsui`)
  2. Any app that opens folders
  3. The system file browser opened at the folder (`ACTION_OPEN_DOCUMENT` with `EXTRA_INITIAL_URI`)
- If none starts, a toast says "Couldn't open the folder: Music/…".
- It no longer asks for write access, which wasn't needed. It also no longer uses `resolveActivity`, which Android 11+ package visibility rules can make return nothing.
- Desktop is unchanged.

## Verification
- `:androidApp:compileDebugKotlin` passes.
- `scripts/build.sh dev --android` passes all checks. The new code is confirmed in the APK. Output: `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-04_2139/`.
  - This APK also contains the convert prototype, unchanged.
- **Not run here** (no device). The project manager checks on the phone: ⋮ on any song → View file location → the song's folder opens.

## Impacts
- No new permissions, dependencies, environment variables or network use. Not committed.
- **Noted:** `scripts/build.sh` asks "Build anyway?" even when there are no pending mockup changes. A possible follow-up is to ask only when there's something pending. That hasn't been changed.
