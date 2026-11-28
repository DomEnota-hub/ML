# GigaAM word-onset research probe

Research only. These Python files are not Android dependencies or app code. They do not download models, upload media, change lyrics, or establish production confidence thresholds.

`ctc_reference.py` is the recovered original reference (synthetic checks were completed previously). `gigaam_timing_probe.py` is the exact probe used for the GTSinger diagnostic with only input/output paths converted to CLI arguments after the measurement. Original run-script and reproducible CLI hashes are recorded separately in the report.

Prerequisites: Python3, NumPy, ffmpeg, ONNX Runtime1.20.0 CPU; verified `v3_ctc.int8.onnx`, `v3_vocab.txt`, `onnx_asr.whl`0.11.0 under ASSETS; local CORPUS contains manifest.json with `revision` and `cases:[{id,path,technique}]`, and each id.json/id.wav pair. GTSinger JSON contains published word start_time/end_time. Corpus recording/annotation hashes are recorded in the report source_manifest. Dataset: https://huggingface.co/datasets/GTSinger/GTSinger , revision4426c862beed558b7e1cb8a4dce7e8c0c83bb208. Yu Zhang et al., NeurIPS2024; research-only CC BY-NC-SA4.0, see the dataset license. No recordings or original words are committed here.

```
python scripts/research/gigaam_timing_probe.py --assets /path/assets --corpus /path/corpus --out /path/report.json
```

The preprocessor follows onnx-asr's exact filter/window tables and NumPy pipeline (Ilya Stupakov, MIT; full notice `docs/research/licenses/onnx-asr-0.11.0-MIT.txt`). Do not substitute standard approximate filters. Unknown words/characters and clips outside the probe's60second limit require inspection; this is not a production long-track runner. Unknown characters must not be silently dropped in any app implementation.

Diagnostic paths use min peak1e-12 **only to measure forced-path timing**. They are not accepted lyrics. Report strict all-token peak.5 separately; neither parameter is calibrated for release. The word-error distribution cannot establish line-onset coverage, full-mix performance, whole-song confidence, Android RAM or runtime. Audio timing, privacy/revocation, long-recording stitching and final acceptance remain separate product gates. See `docs/research/2026-11-28-lyrics-alignment.md`.
