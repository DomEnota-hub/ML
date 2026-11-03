# Approved scope: text interaction and responsiveness

User approved implementation on2026-11-03 after describing these requirements:
- Video text uses the existing direct-manipulation white frame: drag, scale/rotate handle, +1 independent duplicate, X delete.
- Keyboard/Style/Font remain; coordinate/rotation/time sliders for text are removed.
- Independent blue text intervals below video, selected start/end handles; edit/style/trim/copy/delete actions.
- Investigate startup, gallery, music and video-edit latency against relatively smooth1.7.110; do not attribute all lag to Debug without evidence.
- Preserve original media, privacy/permission gates, project history and export behavior.
- Minimal targeted checks; no repeated broad suites, automatic merge or official release.

Implementation choice: reuse shared photo text frame and existing timeline geometry; fix identifiable blocking/unbounded background work in place. No wholesale UI/repository rewrite.
Baseline release branch HEAD004dfababa1c1f4f2dcc2ccfa77ff727631393b0. Local adb has no target; actual phone timing remains unverified until a target is available. Source evidence is not a measured speedup.

## Added user steering: trim recording

Reference: Google Drive Образцы / Обрезка (video/mp4,16dWMyg_qTlVv4gj2mZEaO1KRQx08VhK1,14.07s recording).
Observed: fixed central white cursor; reel scrubs beneath it; active edge follows
finger and kept strip shortens; boundary preview/source clock change while dragging;
after release cropped reel is rebuilt/repositioned. +clip button stays accessible.
Implement source-ruler expansion only during a handle gesture, preserve the active
handle's viewport anchor, collapse/recenter afterward, and reuse a boundary decoder
per gesture. Keep reversible in/out model, source immutability and existing export.
The already-started2305f23APK is intermediate; trim is not included in that artifact.
