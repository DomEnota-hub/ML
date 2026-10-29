# Transparent system navigation and unified mini-player

User asks to remove the opaque three-button system navigation background throughout
all modes/video and make Downloader's mini-player match Gallery/Music with artwork.

Cause: no explicit opt-out from Android's navigation contrast scrim; fullscreen exit
also restored decorFitsSystemWindows=true. The old mini-player renderer was selected
by the Downloader mode and used a music glyph instead of artwork.

Changes: shared transparent-navigation window policy, contrast opt-out API29+, divider
transparent API28+, edge-to-edge main/auth windows and fullscreen exit. Root Compose
theme selects icon contrast. Every separate Compose Dialog/ModalBottomSheet applies
the same window policy. Insets remain: transparency does not remove safe touch gaps.
XML v29 theme also opts out, including startup. Downloader uses the same one remaining
mini-player renderer/controller/artwork/progress and scoped library palette.

Verification budget: user's minimal-check preference persists. Two focused window
policy/fullscreen-transition tests and release build only. No emulator/screenshot or
full-suite run. Previous persistent catalog checks remain complete. Source version
1.7.98 unchanged; signed/public APK unchanged, next release1.7.99/code161.

Ruling: make the background transparent; keep Android navigation buttons functional.
Fullscreen video retains transient system-bar hiding. No global hide-navigation mode.
Actual OEM rendering remains a phone check; no claim of pixel-level device coverage.


Focused debug tests first passed (2 tests); initial assembly succeeded in8m25s.
Source review then found a concrete light-theme exit issue: fullscreen uses white
system glyphs over video and must restore app-theme glyph contrast on exit. Added
explicit app-theme contrast restoration (including legacy/system preferences), plus
both light/dark exit assertions in the same focused case. The final check uses
:app:testReleaseUnitTest --tests '*TransparentNavigationTest' with :app:assembleRelease
to avoid recompiling the debug production variant again. Final release tests:2,
zero failures/errors. Exact command log:/tmp/transparent-navigation-final.log.
Final arm64 assembly completed: BUILD SUCCESSFUL in 5m 24s. Two focused release tests passed; command exit0.

Instagram auth uses its existing system-theme background for glyph contrast. Other
native auth windows retain their theme's glyph appearance. Safe insets and private
playback guards are retained. No previous persistent-catalog checks are repeated.
