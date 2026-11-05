# Fix: Convert to Audio started twice on Android

**Time:** 2026-11-05, ~15:05 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: reported the bug while testing ("clicking on 'Convert to Audio' automatically starts the conversion process before i've even clicked the 'Convert' button") and asked that the desktop be checked too.

## Cause
In `ui/ConvertToAudio.kt` the Convert button ran `startBackgroundConversion?.invoke(…) ?: model.startConversion(…)`.
- On Android, the background path starts the service and the job, and returns null when that works. The `?:` treated that null as "not handled" and started the job a second time.
- The second call failed with "Another conversion is already running". The dialog stayed on its setup screen showing that message, never switched to the progress popup, and the conversion ran in the background with its notification. That is why it looked as if the conversion had started on its own.
- Nothing starts a conversion when the dialog opens.

## Fix
- The Convert button now uses the background path when the platform has one (Android), and otherwise calls the model directly (desktop). Each starts exactly one job.
- Prior version: `version-control/shared/2026-11-05_1501/ConvertToAudio.kt`.

## Desktop
- Desktop has no background path, so it called the model once and was never affected.
- `convertToAudioDialogProgressAndInbox` now also checks that opening the dialog starts nothing and that Convert starts exactly one job, with no "Another conversion…" message.

## Tests
- **New** `DesktopScreenshotTest.convertStartsOnlyOnConvertAndOnlyOnce` covers the Android-style background path: nothing starts on open, then one start, and the progress popup shows. It fails on the old code.
- Both tests pass.

## Builds
`scripts/build.sh dev` passed all checks:
- `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-05_1504/`
- `project-builds/dev-builds/RecordPlayer-linux-0.1.0_2026-11-05_1504/`
