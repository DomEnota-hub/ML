# Native variant A captures

Captured 2026-10-29 from the actual Compose/View tree in Robolectric SDK 34,
390 dp wide, 740 dp content height (the capture contains the surrounding test
activity too). These are native component renders, not emulator/device screenshots.

- [Dark overview](dark-contact.png)
- [Light overview](light-contact.png)
- Individual screens: `dark-*.png` / `light-*.png` (including full photo viewer).

`LibraryVisualCaptureTest` renders the production gallery, albums, album detail,
mixed viewer, music list, playlists, playlist detail, track selection, full player
and equalizer in both themes. The fixture file metadata and offline cached covers
are test data only. No private user files or diagnostic data appear here.

The render harness supplies the same Surface content color as the application's
Scaffold. It draws the native View into a software Canvas: Compose's PixelCopy
helper needs a display redraw callback unavailable in this Robolectric runtime.
The initial PixelCopy failure was a capture limitation, not an app timeout.

Visual comparison against HTML A checked palette, typography, spacing, four-column
media grid, two-column album cards, album-specific header, source selector, round
controls, player cover/control hierarchy, and EQ layout. Native 48 dp touch targets
and system typography are retained as required by the spec.

Not established by these renders: actual screen/system insets on a handset,
active service mini-player rendering, audible EQ transitions, Bluetooth/wired
output, overlay permission behavior or overlay-to-fullscreen playback on a device.
The separate functional suite checks session/queue controls, privacy masking,
selection restoration, DSP signals, and real native overlay seek touch routing.
