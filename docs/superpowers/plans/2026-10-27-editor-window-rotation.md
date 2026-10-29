# Actual editor window bounds and rotation

Reported: delete footer still disappears; rotating returns to the initial editor.

Root evidence: Compose UI AndroidDialog 1.7.4 overrides both MeasureSpecs using configuration
screenWidthDp/screenHeightDp when usePlatformDefaultWidth=false. Its subsequent window.setLayout
uses those measured child dimensions. When configuration height exceeds the actual usable window,
the footer is outside the screen, despite its fixed Column position. Existing JVM math tests did
not exercise this. A Robolectric Compose regression reproduces the old footer below physical
display height by inflating screenHeightDp while keeping physical display metrics fixed.

Fix: keep Dialog's normal measuring path (usePlatformDefaultWidth=true), expand the underlying
Android window with MATCH_PARENT, and let decor/system-inset fitting constrain the real content.
Preserve existing draft/cancel behavior and the fixed footer. No Compose version upgrade.

Rotation: MainActivity currently recreates and drops remember-only editor/navigation/bitmap state.
Handle orientation|screenSize configuration updates in place so Compose can reflow without losing
the live draft. This addresses rotation, not process-death persistence.

- [x] Establish Robolectric Compose layout tests and reproduce configuration-height overflow.
- [x] Verify footer visibility before/after font scrolling and fix the window measure path.
- [x] Verify activity config flags (RED on old manifest), implement orientation handling.
- [x] Verify draft/font/deletion after a landscape configuration update.
- [x] Full suite and ARM64 1.7.82/144 build, independent review, signed APK upload.

Tests use Robolectric (SDK34/35), with Compose UI test dependencies confined to debug/test builds.
Device OEM behavior remains a physical-device check; there is no connected adb device.

Verification: old configuration-height and MainActivity rotation-flag tests failed as expected.
New measurement keeps delete bounds within physical1600px (bottom1584px). Full suite: 499 tests,
zero failures/errors, including six Compose UI cases across SDK34/35. Draft/font/delete behavior
survives configuration changes; test host shares MainActivity orientation|screenSize flags.
Independent review found no blocker. ARM64 release builds; APK v2/v3 signature verified.
