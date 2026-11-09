#!/usr/bin/env python3
"""Research gate 4.2: inspect the ACTUAL published DExNet checkpoint safely.

Third-party original: https://github.com/jjhuangcs/DExNet (Apache-2.0).
Published checkpoint: Google Drive file id 1jkhCvyrQAQZtLaxdDWDkkSOF0NZ12aJp.
No checkpoint binary, APK or source archive is committed or uploaded to GitHub.
This is checkpoint provenance/compatibility inspection, NOT completed model QA.
"""
import hashlib
import json
import pathlib
import tempfile
import time

PUBLISHED_DRIVE_ID = "1jkhCvyrQAQZtLaxdDWDkkSOF0NZ12aJp"
PUBLISHED_BYTES = 118_442_981
MAX_BYTES = 140_000_000


def main():
    import gdown
    import torch
    with tempfile.TemporaryDirectory(prefix="ml-dexnet-inspect-") as work:
        path = pathlib.Path(work) / "DExNet.pt"
        begin = time.monotonic()
        result = gdown.download(id=PUBLISHED_DRIVE_ID, output=str(path), quiet=False)
        if not result or not path.is_file():
            raise RuntimeError("Published DExNet weights were not downloaded")
        size = path.stat().st_size
        if size != PUBLISHED_BYTES or size > MAX_BYTES:
            raise RuntimeError(f"DExNet published bytes unexpected: {size}")
        h = hashlib.sha256()
        with path.open("rb") as f:
            while chunk := f.read(1024 * 1024):
                h.update(chunk)
        print("DEXNET_DOWNLOAD_VERIFIED", json.dumps({
            "bytes": size, "sha256": h.hexdigest(),
            "elapsed_s": round(time.monotonic()-begin, 2)
        }), flush=True)
        # Do not turn weights_only=False on: a checkpoint with custom object
        # globals is not safe to execute from third-party input.
        state = torch.load(str(path), map_location="cpu", weights_only=True)
        if not isinstance(state, dict):
            raise RuntimeError("Unknown checkpoint structure")
        keys = sorted(str(x) for x in state.keys())
        print("DEXNET_TOP_LEVEL_KEYS", json.dumps(keys[:35]), flush=True)
        if len(keys) < 1:
            raise RuntimeError("Empty checkpoint")
        tensors = [x for x in state.values() if isinstance(x, torch.Tensor)]
        nested = state.get("weight")
        if isinstance(nested, dict):
            tensors.extend(v for v in nested.values() if isinstance(v, torch.Tensor))
        total = sum(x.numel() for x in tensors)
        print("DEXNET_TENSOR_STATS", json.dumps({
            "tensors": len(tensors), "parameters": total,
            "param_mem_mib": round(total*4/1024/1024, 1),
            "torch_version": torch.__version__,
        }), flush=True)
        if total < 1000:
            raise RuntimeError("No trained tensor weights located")
        print("DEXNET_CHECKPOINT_ACCESS_AND_SAFE_DESERIALIZATION_PASS", flush=True)
        print("MODEL INFERENCE/ONNX/ANDROID/QUALITY/WEIGHTS_LICENSE not yet verified", flush=True)


if __name__ == "__main__":
    main()
