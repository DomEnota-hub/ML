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
