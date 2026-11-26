# ML-Models: QA230-05 distribution gate (26.11.2026)

Status: PARTIAL / SIGNED RELEASE AND RIGHTS BLOCKED. This document is a design/approval record, not a deployed catalog or release authority.

## Current supported sources

The installed Android model stores and SHA-256 pins remain the authoritative trust boundary. The new AiModelCatalog groups five model descriptions, pinned binary identities and source provenance. The current three upstream checks are advisory only. No server response authorizes changed weights.

- LaMa: sapienkit/LaMa-ONNX publisher card reports Apache-2.0, matching existing SHA. Exact byte length and immutable revision need direct artifact receipt before publishing a release catalog.
- MobileSAM: Acly/MobileSAM at 0d3b403339b4674a82493d5e97964dd78089ddc8 (export publisher MIT); encoder and single-mask decoder are one bundle. Existing exact sizes and digests in MobileSamModelStore.
- EfficientDet-Lite0: official MediaPipe URL pinned to float32/1; exact size and SHA in PersonDetectorModelStore. The HF mirror metadata is advisory; retain official source and recheck model terms before redistribution.
- SRNet: existing MIT-licensed original checkpoint and verified conversion (THIRD_PARTY_NOTICES.md). Offline installation and explicit pinned network download remain available.
- DExNet + VGG19: exact tested ONNX SHA/bytes in ReflectionDExModelStore; the original code license is not proof of redistribution rights for the exported combined model. Retain explicit verified SAF import (or existing audited bundled build). Do not host or offer an unauthorized binary.

Sources checked: https://huggingface.co/sapienkit/LaMa-ONNX, https://huggingface.co/Acly/MobileSAM/blob/0d3b403339b4674a82493d5e97964dd78089ddc8/README.md, https://github.com/jjhuangcs/DExNet, THIRD_PARTY_NOTICES.md and docs/qa/stage4_reflection_model_gate_20261109.md.

## Publication and update contract (not active)

Owner provisioned https://github.com/DomEnota-hub/ML-models. Initial inventory and existing MIT SRNet conversion published with attribution. Catalog SHA fec296a0c1de1c9eaa0193f984525a6323aeb760. Android reads inventory as advisory metadata, never as installation authorization. SRNet explicitly downloads the pinned identical artifact or installs offline. DExNet remains verified import. Signed independent update activation is NOT implemented.

Before independent update activation:
1. Provision the owner-controlled catalog/large-file hosting and offline catalog signing key, keep the private key outside GitHub; provision the public verification key in the app. Define versioned canonical signed manifests, monotonic release numbers, supported app/engine versions, exact filenames/sizes/SHA-256, immutable HTTPS URLs, license and compatibility evidence, expiration and rollback policy.
2. For every artifact establish distribution rights, verify actual bytes, tensor/metadata/shape/operator contracts, CPU inference parity, quality and memory budgets. Model card license alone does not clear a composite model.
3. Stage complete bundles (including both MobileSAM components), verify all contents before activation, switch one durable active-generation pointer atomically, keep the working generation until successful activation. Serialize with ORT/TFLite session lifecycle; support cancellation and process death. Never change installed weights automatically on metadata check.
4. Test invalid signature, unsupported compatibility, replay, size/hash mismatch, incomplete bundle, failed activation, disk shortage, interrupted download and rollback, and existing editor parity. Existing pinned installs and offline/import fallbacks must remain usable without network.
5. Only after owner-controlled publication and end-to-end verification, show an Update action for a specifically approved compatible release. Unreviewed upstream changes remain review-only.

The compact UI is implemented independently of publication. QA230-05 cannot be marked complete merely because the current catalog is represented in code.
