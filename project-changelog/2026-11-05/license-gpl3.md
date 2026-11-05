# License: GPL-3.0

**Time:** 2026-11-05 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: "Let's go with GPL-3.0", after a comparison of MIT, Apache-2.0 and GPL-3.0.

## Details
- **New `LICENSE`:** the unmodified GNU GPL v3 text, copied from the system's `/usr/share/common-licenses/GPL-3` (no download). SHA-256 `3972dc97…986`, the same as the text the GNU project publishes.
- **`README.md`:** a new License section.
  - It states **GPL-3.0-or-later**: version 3 or, at the user's option, any later version (project manager's choice). The `LICENSE` file is the same GPL v3 text either way.
  - It lists the main libraries and their licenses, all compatible:
    - Apache-2.0 code can be combined into GPL-3.0 works.
    - jaudiotagger and GStreamer are LGPL-2.1. GStreamer is used from the system on desktop and isn't bundled.

## Impacts
- **Required by F-Droid.** GitHub will show the license once `LICENSE` is pushed.
- **Anyone distributing the app or a modified version** must release its source under GPL-3.0.
- No code, builds or dependencies changed. Not committed.

## Follow-ups
- F-Droid metadata: license field `GPL-3.0-or-later`, plus a description and screenshots.
