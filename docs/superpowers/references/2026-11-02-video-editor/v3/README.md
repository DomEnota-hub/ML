# v3 — panels from supplementary text recording

The user supplied Screenrecorder-2026-11-02-13-32-17-464.mp4:
2,021,032bytes,1080×2400,HEVC/AAC,7.383901seconds.
Local attachment copied to `/workspace/video-editor-reference/supplement/text-ui-reference.mp4`.
Raw reference remains outsideGit. It shows text keyboard, Style, Font, not Save.

## Implemented from new source

- Measured text-panel placement: cropped preview, entry/check, Keyboard/Style/Font tabs.
- Style palette, transparency0–100, Bold/Outline/Shadow, Left/Center/Right alignment.
- The only visible font option is Default; no invented downloadable font catalogue.
- Editable preview with selection boundary/+1/delete/resize controls. Position, resize
  and rotation; separate text layers retain their own style and can be selected again.
- PNG/JPG frame export renders layer colour/alpha/bold/outline/shadow/alignment/rotation.
- System keyboard on touch devices; a working illustrative Russian keyboard on desktop.
  Desktop Gboard service buttons are illustrative, not an actual keyboard-provider clone.
- Custom colour uses the browser colour picker. The exact native sub-dialog behind the
  sample's A/palette button was not demonstrated; do not call that native dialog copied.

## Still waiting for user source

A follow-up async question requests the screen AFTER pressing Save. It is absent in
this7second supplement. Save remains explicitly pending in the mockup, not fake MP4
output. Other unresolved v2 states (project aspect, Titles, full preset catalogue) are
still unresolved. Approximate CSS effects do not claim the original proprietary LUTs.
The core task is not fully complete; continue when the remaining reference arrives.

## Minimal verification

node --check text-panels.js and editor.js: PASS.
check_text.py: keyboard/style/font, palette, alpha55%, bold/outline/shadow toggles,
alignment, separate layers and selection after moving an overlapping layer, actual
styled PNG download, 360px layout: PASS, no JS errors.
A first selector matched hidden and visible Apply buttons; narrowed the test to
visible Apply. No product bug was hidden by skipping a failed assertion.
After small source-measured CSS adjustments the same narrow scenario was run once
again to regenerate screenshots; it passed. No old Android or audio suites rerun.

## Delivery

Standalone HTML169895bytes (styles/assets/scripts embedded).
Drive upload and metadata readback verified:
https://drive.google.com/file/d/1Td-BK6HvjQ3y4qIkvp1DI1UyQF2wLtuA/view?usp=drivesdk
Name: MediaLoader_Видеоредактор_по_образцу_v3_текст.html
Folder: «Прототипы», id1AC68IE5mvzi_lBQMmj1HCu9hsZITUFX8.
Previousv1/v2 unchanged. DownloadHTML, then open in a browser.

`build.py` takes editor.js + text-panels.js + editor.css + index.template.html +
assets.json and produces the standalone file. No Android code/version/build changed.
Unrelated pending main-mode settings edits remain untouched.
