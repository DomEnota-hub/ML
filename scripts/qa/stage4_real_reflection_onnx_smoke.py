#!/usr/bin/env python3
"""Stage 4 research ONLY: real upstream reflection-separation ONNX CPU inference.

No fake reflection model, no LaMa and no blur proxy. This test fetches a genuine
third-party pretrained model in an ephemeral CI directory. It NEVER republishes
the checkpoint, produces an APK, or claims the Android reflection mode is ready.

Upstream project code is MIT; DINOv3 backbone weights are under Meta's distinct
DINOv3 License. Distribution of any derivative model needs a separate license
audit, notices and rights assessment before user-facing packaging.
"""
from __future__ import annotations
import hashlib
import io
import json
import os
import pathlib
import resource
import tempfile
import time
import urllib.request
import numpy as np
import onnxruntime as ort
from PIL import Image

MODEL = (
    "https://github.com/PINTO0309/reflection-removal/releases/download/onnx/"
    "dinov3_vits16_disthyper_residual_gennerator_640x640_320x320.onnx"
)
ASSET_BASE = "https://storage.googleapis.com/mediapipe-assets/tasks/testdata/vision/"
FIXTURES = {
    "pose": ("pose.jpg?generation=1782185208547605",
             "c8a830ed683c0276d713dd5aeda28f415f10cd6291972084a40d0d8b934ed62b"),
    "burger": ("burger.jpg?generation=1782184424868164",
               "97c15bbbf3cf3615063b1031c85d669de55839f59262bbe145d15ca75b36ecbf"),
}
MAX_MODEL_BYTES = 105 * 1024 * 1024

def fetch(url: str, path: pathlib.Path, max_bytes: int, expect_sha: str | None = None):
    request = urllib.request.Request(url, headers={"User-Agent": "ML-Stage4-Reflection-QA/1.0"})
    sha = hashlib.sha256()
    total = 0
    with urllib.request.urlopen(request, timeout=95) as response, path.open("wb") as out:
        if response.status != 200:
            raise RuntimeError("Model endpoint returned HTTP " + str(response.status))
        while True:
            chunk = response.read(128 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > max_bytes:
                raise RuntimeError("Model exceeds RAM/disk safety cap")
            sha.update(chunk)
            out.write(chunk)
    value = sha.hexdigest()
    if expect_sha and value != expect_sha:
        raise AssertionError("Upstream fixture/model SHA-256 changed")
    return {"bytes": total, "sha256": value}

def load_rgb(photo: pathlib.Path, height: int, width: int):
    with Image.open(photo) as im:
        return np.asarray(im.convert("RGB").resize((width, height),
                          Image.Resampling.BILINEAR), dtype=np.float32) / 255.0

def quality(base: np.ndarray, other: np.ndarray):
    mse = float(np.mean((base-other)**2))
    mae = float(np.mean(np.abs(base-other)))
    psnr = -10 * np.log10(max(mse, 1e-10))
    return {"mae": round(mae, 5), "psnr_db": round(float(psnr), 3)}

def main():
    with tempfile.TemporaryDirectory(prefix="ml-reflection-stage4-") as directory:
        temp = pathlib.Path(directory)
        filename = temp / "reflection.onnx"
        meta = fetch(MODEL, filename, MAX_MODEL_BYTES)
        print("MODEL_DISCOVERY", json.dumps(meta), flush=True)
        if meta["bytes"] < 50 * 1024 * 1024 or meta["bytes"] > MAX_MODEL_BYTES:
            raise AssertionError("Upstream pretrained checkpoint missing or unexpected")

        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 2
        opts.inter_op_num_threads = 1
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        print("CPU_ONNX_LOADING", flush=True)
        session = ort.InferenceSession(str(filename), opts, providers=["CPUExecutionProvider"])
        inputs = session.get_inputs()
        outputs = session.get_outputs()
        print("MODEL_TENSORS", json.dumps({
            "inputs": [{"name": x.name, "shape": x.shape, "type": x.type} for x in inputs],
            "outputs": [{"name": x.name, "shape": x.shape, "type": x.type} for x in outputs],
        }), flush=True)
        if len(inputs) != 1:
            raise AssertionError("Not an independent complete image inference model")
        shape = inputs[0].shape
        if len(shape) != 4 or shape[0] not in (1, "batch_size", None):
            raise AssertionError("Unexpected input tensor shape")
        h = shape[2] if isinstance(shape[2], int) else 640
        w = shape[3] if isinstance(shape[3], int) else 640
        if not (128 <= h <= 640 and 128 <= w <= 640):
            raise AssertionError("Unsupported input geometry")

        images = {}
        for key, (relative_url, expected_sha) in FIXTURES.items():
            file = temp / (key + ".jpg")
            print("FIXTURE", key, json.dumps(fetch(ASSET_BASE + relative_url, file,
                       10 * 1024 * 1024, expected_sha)), flush=True)
            images[key] = load_rgb(file, h, w)

        ground = images["pose"]
        layer = images["burger"]
        scenes = {
            "negative_clean_pose": ground,
            "mild_reflection": np.clip(.85*ground+.15*layer, 0, 1),
            "strong_reflection": np.clip(.65*ground+.35*layer, 0, 1),
        }
        records = {}
        for key, image in scenes.items():
            feed = np.transpose(image, (2, 0, 1))[None].copy()
            start = time.perf_counter()
            out = session.run(None, {inputs[0].name: feed})
            elapsed = round(time.perf_counter()-start, 3)
            rgb = out[0]
            if len(rgb.shape) != 4 or rgb.shape[0] != 1 or rgb.shape[1] != 3:
                raise AssertionError("No neural RGB transmission output")
            pred = np.transpose(rgb[0], (1, 2, 0))
            if pred.shape[:2] != (h, w):
                with Image.fromarray((np.clip(pred, 0, 1)*255).astype(np.uint8)) as im:
                    pred = np.asarray(im.resize((w,h)), dtype=np.float32)/255
            if not np.isfinite(pred).all():
                raise AssertionError("Nonfinite transmission image")
            pred = np.clip(pred, 0, 1)
            metrics = {
                "input": quality(ground, image),
                "restored": quality(ground, pred),
                "delta_from_input": quality(image, pred),
                "cpu_seconds": elapsed,
            }
            records[key] = metrics
            print("REFLECTION_SCENE", key, json.dumps(metrics), flush=True)
        print("PEAK_RSS_KIB", resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
              flush=True)
        # Preflight (not final acceptance): output must be nontrivial and not
        # catastrophically damage the scene. Exact benchmark quality gates
        # require proper reflection/transmission dataset pairs.
        if records["mild_reflection"]["delta_from_input"]["mae"] < .005:
            raise AssertionError("Model is effectively an identity transform")
        if records["negative_clean_pose"]["restored"]["mae"] > .30:
            raise AssertionError("Model heavily damages a reflection-free scene")
        if records["mild_reflection"]["restored"]["mae"] >= records["mild_reflection"]["input"]["mae"]:
            raise AssertionError("Neural reflection remover does not improve mild synthetic blend")
        print("REAL_REFLECTION_ONNX_CPU_PREFLIGHT_PASS; rights/Android/final QA not yet approved",
              flush=True)

if __name__ == "__main__":
    main()
