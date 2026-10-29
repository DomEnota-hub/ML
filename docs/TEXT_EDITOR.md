# Photo and video text layers

The text tool edits a draft of up to eight independent layers. Applying the draft returns it to
the parent editor; canceling discards the draft after confirmation. The parent editor's existing
unsaved-change and reset behavior includes text layers. Saving always creates a new media file.

Positions and font sizes refer to the final cropped and rotated frame. Both the preview and
export use `TextLayerRenderer`. Photos apply text after crop, mirroring and rotation. Video uses
a Media3 bitmap overlay after crop and clockwise rotation. Video layer intervals are half-open
and relative to the start of the trimmed output, not the original source. The source-video
preview subtracts the trim offset; exported video uses clip-relative timestamps. Shortening
the output clamps layer end times and omits layers outside the retained duration.

The bitmap used by the video overlay is reused and updated at layer visibility boundaries;
it is not recreated on every frame. Its longest side is capped at 1920 pixels. Existing exports
without text use an empty layer list and create no text overlay.

The bundled font library contains 23 variants, including two light faces, with Latin and
Cyrillic glyphs. Font licenses are distributed under `assets/text-fonts/LICENSE-*.txt`.
`tools/subset_editor_fonts.py` retains these scripts and renames derivative font metadata.

Font matching is experimental visual comparison against the 23 bundled variants. The user
selects a tight sample rectangle, checks the recognized source text and can correct it manually.
On-device Tesseract OCR uses bundled `tessdata_fast` Russian and English models. Model sources:
https://github.com/tesseract-ocr/tessdata_fast (Apache 2.0 license in `assets/ocr`).
Confident OCR starts matching automatically; uncertain recognition asks for manual correction.
Matching compares normalized glyph contours with a symmetric distance transform, ink density
and aspect ratio. It does not identify an arbitrary external font or guarantee a match on noisy
backgrounds. It proposes up to three fonts, applies the closest only when the score passes its
threshold, and leaves all manual font controls available. Nothing is uploaded.

All Compose sliders use `AppSlider`, with a circular thumb and a thin track. The text palette
includes gray in place of its previous final purple swatch.

The font picker groups the 23 faces into eight families. Outlined family rows display the
active variant; the adjacent arrow expands the family's variants without selecting a font.
Text actions use outlined buttons and the current palette swatch has a highlighted border.

Selected text has a preview-only rotated frame, computed from the same StaticLayout as the
export. Drag inside the frame to move; drag the circular corner handle to change size and angle
together. The handle has a 48dp touch target and stays within the preview when a corner extends
beyond it. Size and angle have no sliders. Pointer vectors are measured from a fixed text center
and drag origin; size remains 0.02–0.25, angles wrap at ±180 degrees. Outside the frame, gestures
remain available to the scroll container. Invisible and blank layers have no frame.

The dialog owns system/keyboard insets, a pinned preview and a weighted scrolling controls area,
so the last actions remain reachable above the navigation bar. Short viewports use a smaller
preview; below 360dp the preview/playback controls are hidden and playback pauses, preserving
space for editing with the keyboard. Video adds separate text and timing tabs.

Video text timing uses a local ExoPlayer clipped to the trimmed interval, with the same crop and
rotation effects as export. Playhead values remain clip-relative. Original audio is audible for
placing text against speech, including when export is configured to remove audio. The preview
can play the chosen text interval and stop at its end. Start/end sliders seek the visible video;
buttons mark either boundary at the playhead or seek to an existing boundary, with 100ms steps.
All text is drawn as a preview-only Canvas overlay, so layer edits do not rebuild the player.
Bitmap frame extraction pauses during playback and resumes on pause/seek. Font sample selection
requires a bitmap matching the paused timestamp and displays that bitmap; playback exits sample
selection. Playback pauses in the background and the player is released on leaving the editor.

The text palette includes an eyedropper and the selected color's HEX value. Picking hides editor
text/selection overlays and displays the source bitmap. Tap or drag on the image to inspect a
pixel through an unfiltered magnified region, then release to apply its exact decoded ARGB value.
Letterbox touches do not sample a color; cancel preserves the layer's previous color. No averaging
or font-color inference is performed. Video pauses first and picking waits for a bitmap matching
the paused playhead. Seeking, playback, timing tab/layer changes or a minimal viewport cancel the
picker. Custom colors use the existing TextLayer color value in photo/video preview and export.

Deletion is a fixed outlined footer action outside the scrolling font/settings list. The list
uses the remaining space; expanding fonts no longer changes the delete button's position.
The preview supports 1–6x two-finger zoom/pan, plus minus/plus/reset buttons on tall viewports.
Compact layouts omit the extra zoom toolbar and main video slider to leave room for settings.
Zoom/pan transforms the image and text together without modifying layer geometry or exports.
Two fingers cancel a one-finger text/sample/color gesture before transforming the viewport.
Pan bounds use the fitted image rectangle so letterboxes cannot allow the image offscreen.
The selection handle clamps to the inverse-mapped visible area and retains its screen size.
Color picking inverse-maps touches and keeps the magnifier at screen size. Zooming video pauses
at the current playhead and uses a bitmap; playback and viewport-size changes reset the zoom.

`EditorWindow` uses the normal Compose Dialog measuring path and explicitly sets the Android
window to MATCH_PARENT. It avoids Compose 1.7.4's full-width-dialog height override based on
Configuration.screenHeightDp, which can exceed the actual visible window. System bars/IME still
constrain the content. MainActivity handles orientation and screenSize configuration changes in
place, retaining the live editor/draft state while Compose reflows. This does not add persistence
for process death. Robolectric Compose tests cover footer visibility before/after font scrolling,
configuration-height overflow, and retained text/font/deletion after a landscape change.

JVM tests cover visibility boundaries, shortened clips, invalid layer geometry, backwards
compatible edit defaults, and the font files' Cyrillic cmap coverage and license presence.
Device checks still required: photo save with crop/rotation/LaMa, text playback and video export
with trimming, multiple timed layers, outline/color agreement, gestures and keyboard layout.
