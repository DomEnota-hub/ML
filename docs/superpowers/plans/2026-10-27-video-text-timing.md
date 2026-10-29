# Video text timing and reachable controls

Goal: fix unreachable bottom actions and choose text timing while seeing and hearing the clip.
User request: 2026-10-27 screenshot and feedback on selecting video text intervals.

Design: safe system/keyboard insets wrap the dialog. Header and responsive preview stay above a
weighted scrolling controls area. A local clipped ExoPlayer uses existing crop/rotation effects;
text remains a preview Canvas overlay. Play/pause and a round-thumb timeline remain visible.
Timing controls come before styling, seek on adjustment, and offer start/end at current playhead,
small frame steps, and playback restricted to the chosen interval. Photos keep their existing
editing controls. Frame extraction is suspended during playback to avoid repeated bitmap decode.

Tasks:
- [x] Fix dialog insets and pin preview, keeping bottom controls scrollable.
- [x] Add clipped video playback with lifecycle cleanup and no automatic playback.
- [x] Add/test pure clip-relative timing operations and wire contextual interval controls.
- [x] Compile, run full tests, review new lifecycle/trim/scrub paths, build signed 1.7.79/141.

Checks: trim-relative coordinates, nonempty intervals, ends exclusive, playback stops on selected
end, seeks at clip end permit replay, no frame extraction during playback, no audio after exit
or background, short/landscape/keyboard layouts retain a scrolling controls viewport.

Verification: timing tests initially failed on missing API, then passed. Full suite 482 tests,
zero failures/errors. ARM64 release build successful. Independent review found replay-at-end
and stale OCR-sample cases; both corrected. Insets/keyboard/touch/audio need device verification;
no adb target is connected.
