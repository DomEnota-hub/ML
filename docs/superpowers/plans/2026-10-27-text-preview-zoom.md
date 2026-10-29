# Text preview zoom and pinned deletion

User feedback: deletion remains unreachable after fonts; preview is too small for precise editing.
Delete action moves out of the scroll list into a fixed footer above system/keyboard insets.
Preview gets 1–6x two-finger zoom/pan, outlined minus/plus/reset controls. A single graphics layer
transforms source and text together; text drag/sample coordinates use Compose's inverse mapping.
The color picker stays outside that layer and explicitly inverse-maps touches, preserving its
screen-size magnifier. Zoom is preview-only and never enters text geometry or export specs.

- [x] Red/green tests: focal anchoring, pan bounds, reset, source-pixel mapping and scale limits.
- [x] Fixed footer deletion and shared zoom gesture container; cancel text drag when pinch takes over.
- [x] Keep color picker and selection handles usable at zoom, reset on frame viewport resize.
- [x] Full tests, independent review, signed ARM64 1.7.81 /143 and APK upload.

UI validation limitation: SDK has no emulator, and no adb target was available in prior releases.
Do not claim device reproduction of the scroll issue; pinning removes dependence on list scrolling.

Verification: zoom tests first failed on missing API; initial reset test detected negative-zero
state, fixed by canonical reset. Review found offscreen handles and letterbox pan limits; added
regression cases before extending implementation. Six focused tests pass, full suite 492 tests
passes with zero failures/errors. ARM64 release build successful and APK signature v2/v3 verified.
No adb device is connected; phone layout and multitouch remain unverified on device.
