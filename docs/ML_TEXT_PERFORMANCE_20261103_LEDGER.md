# Text frame / performance continuation

Baseline:0b31ac8, PR54; comparison1.7.110 release branch004dfab.
No local adb target. Do not infer measured phone speedup from source inspection.

Task1: complete — targeted WaveformDecodeGate/WorkPolicy, existing AudioWaveformCache,
LibraryValidationGate and PlaylistRepository tests PASS; compile PASS(41s).
RED: missing gate/policy were observed before implementation.
Changes: idle-only bulk work with legacy-job migration; visible-track requests remain
immediate; one uncached decoder at a time and cooperative cancellation; no startup
scheduling on main; publish library before coalesced alias persistence; first/final
history batches retained with at most4intermediate publications/sec.
Ruling: use system idle for speculative whole-library decode, not for user-requested
tracks — keeps waveforms available without foreground CPU competition; full library
precompute may finish later. No functionality/data/export formats removed.

Task2: complete — selected9classes PASS(26s), preceding instrumented compile PASS.
Text range/copy tests were introduced RED before implementation. Existing shared
photo-frame gesture implementation is reused; photo defaults are preserved.
Video frame: white corners,+1independent copy,Xdelete,drag and combined scale/rotate.
Frame and lane drags suspend history recording until the gesture finishes/cancels.
Text size/coordinates/rotation/time sliders are absent; shared Style hides only
its size slider for video. Keyboard/Style/Font remain, with compact confirm input.
Blue independent ranges use video scroll/scale; selected layer is brought into
view, and edit/style/trim/copy/delete actions replace the editing footer.
Continuous UI preview now samples latest parameters every50ms, replacing75ms
debounce that could postpone every update until the finger stopped. Sampling
regression test proves intermediate updates while changes continue.
No export/shader/codec implementation changed; no full MP4 matrix rerun.
