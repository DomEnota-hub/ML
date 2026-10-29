# Text color eyedropper

User request: copy an existing text color and use it for an editable text layer.
Implementation: outlined eyedropper button next to the palette; source-only paused preview;
tap/drag to inspect exact ARGB pixels with a magnified region and HEX label, release to apply.
Ignore letterboxes. Cancel leaves the current color untouched. Video uses only a frame matching
the paused playhead; playback/seek/tab/layer changes cancel picking. Keep manual swatches.

- [x] Test fit-to-view pixel mapping, letterboxes, invalid inputs and exact color formatting.
- [x] Implement a pure mapping helper and preview-only picker overlay.
- [x] Wire exclusive sampling/picking modes, fresh-frame gate and selected-color display.
- [x] Compile/test/review and build signed ARM64 1.7.80/142; no default vertical slider handles.

Verification: focused tests first failed on missing sampling API, then passed. Full suite 486
tests, zero failures/errors. ARM64 release build successful; signature v2/v3 verified. Independent
review found consumed cancellation events could commit a color; overlay now checks consumption
and commits only changedToUp() transitions. No adb device: touch/magnifier interaction still
requires on-device verification.
