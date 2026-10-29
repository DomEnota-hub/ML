# Compact text editor controls

Approved in chat: replace the 23-entry font column with one outlined row per family and an
expand arrow for its variants. Show the active variant in the collapsed row and render font
samples in their own faces. Outline clickable text actions and show selected swatches clearly.
Selected text has a rotated frame, can be dragged inside it, and has a circular corner handle
for simultaneous rotation and proportional resizing. Give the handle a generous touch target.
Remove size and rotation sliders. Keep outline and video timing sliders circular.
Apply the same controls to photos and video frames; selection controls never enter exports.

Approaches: keep separate sliders (rejected by user); direct frame manipulation (chosen for
compactness and alignment with the screenshot). Family grouping keeps all 23 font variants.
Use the shared StaticLayout geometry so the frame and exported text agree. No new dependency.
