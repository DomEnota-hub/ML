#!/usr/bin/env python3
"""Stage 3 research gate: licensed real SRNet weights -> PyTorch -> ONNX -> CPU.

Model/code: zhiyiYo/Specular-Removal (MIT), immutable upstream commit below.
A real pretrained checkpoint is necessary; dummy models or synthetic-only
forward passes must not be accepted as stage-3 neural inference.
The test generates its own synthetic samples (no external private photos).
This script does NOT run on Android, does NOT ship models or APKs.
"""
from __future__ import annotations

import ast
import base64
import os
import hashlib
import io
import json
import math
import pathlib
import tempfile
import time
import urllib.request
from urllib.parse import quote

SOURCE_COMMIT = "6f44a130d2b20568f1a1b1159e32a4ec6b46ef19"
BASE = ("https://raw.githubusercontent.com/zhiyiYo/Specular-Removal/"
        + SOURCE_COMMIT + "/")
NETWORK_FILE = "specular_removal/network.py"
WEIGHTS_FILE = "model/SRNet_2021-07-23_23-00-22.pth"
SOURCE_BLOB_SHA1 = "a8d945260a4c32354057dc88e77a811a8c878875"
MAX_WEIGHTS_BYTES = 2 * 1024 * 1024
PINNED_WEIGHTS_SHA256 = "d20a8b1021cd150cba0fce6738fac997f9283390de134756ecc9710245c2cba8"
PINNED_NETWORK_SHA256 = "472103bbc1bc6e3ab6839af147e12d3edec1110d3a8da891929c7c571a5b783f"
BUNDLED_MODEL_BYTES = 230_221
BUNDLED_MODEL_SHA256 = "1538f5800858c329e93576566d40f568fcc07d8fdc674bca454fbfc4771c63e4"
REAL_SAMPLES = {
    "glossy_wood_table": ("resource/images/木桌.png",
        "878f9f4248dfffbcf4ad882eba0dab3140ef07ea"),
    "plastic_container": ("resource/images/塑料盒.png",
        "d5c59b4baf393e61cb6a550d0551e951710e92f5"),
    "orange_surface": ("resource/images/橙子.png",
        "6475490831ea2b3de1bc14e26504657f919338e4"),
}


def download(url: str, max_bytes: int) -> tuple[bytes, str]:
    request = urllib.request.Request(url, headers={"User-Agent": "MediaLoader-Glare-QA/1"})
    sha = hashlib.sha256()
    parts = []
    total = 0
    with urllib.request.urlopen(request, timeout=90) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}")
        while True:
            chunk = response.read(64 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > max_bytes:
                raise RuntimeError("Downloaded neural model exceeds memory cap")
            sha.update(chunk)
            parts.append(chunk)
    return b"".join(parts), sha.hexdigest()


def build_network_from_licensed_upstream(text: str):
    import torch
    import torch.nn.functional as F
    from torch import nn

    # Extract the exact six MIT-licensed model classes from pinned upstream,
    # excluding predict() methods that depend on PIL/GUI helpers.
    # No arbitrary upstream code is executed (including __main__).
    mod = ast.parse(text)
    keep = {"EncoderBlock", "ConvBlock", "DecoderBlock",
            "PartialConvBlock", "CDFFBlock", "SRNet"}
    nodes = []
    for node in mod.body:
        if isinstance(node, ast.ClassDef) and node.name in keep:
            if node.name == "SRNet":
                node.body = [method for method in node.body
                             if not isinstance(method, (ast.FunctionDef, ast.AsyncFunctionDef))
                             or method.name in ("__init__", "forward")]
            nodes.append(node)
    if {node.name for node in nodes} != keep:
        raise AssertionError("Upstream architecture differs: stop, do not invent a model")
    env = {"torch": torch, "nn": nn, "F": F}
    compiled = compile(ast.fix_missing_locations(ast.Module(body=nodes, type_ignores=[])),
                       filename=NETWORK_FILE, mode="exec")
    exec(compiled, env)
    return env["SRNet"]()


def synthetic_pair(width: int, height: int):
    import numpy as np
    yy, xx = np.indices((height, width), dtype=np.float32)
    # A deterministic diffuse scene with edges/texture. Saturated highlights
    # have fundamentally missing information and cannot be guaranteed recoverable.
    base = np.stack([
        .14 + .40 * xx / width + .11 * np.sin(yy / 9),
        .18 + .38 * yy / height + .08 * np.cos(xx / 11),
        .28 + .24 * (xx + yy) / (width + height),
    ], axis=0).astype(np.float32)
    base[:, 62:150, 118:126] *= .35  # narrow print-like dark feature
    base = np.clip(base, 0, 1)
    lobe = 1.12 * np.exp(-(
        ((xx - width*.5)/(width*.095))**2 +
        ((yy - height*.48)/(height*.16))**2))
    affected = lobe > .10
    shiny = np.clip(base + lobe[None], 0, 1)
    return base, shiny, affected


def test_real_photo(runtime, name: str, source: str, blob_sha: str):
    import numpy as np
    from PIL import Image

    data, _ = download(BASE + quote(source, safe="/"), 6_000_000)
    git_blob_sha = hashlib.sha1(b"blob " + str(len(data)).encode("ascii") +
                                b"\0" + data).hexdigest()
    if git_blob_sha != blob_sha:
        raise AssertionError("Real photo fixture was modified: " + name)
    with Image.open(io.BytesIO(data)) as photo:
        rgb = photo.convert("RGB")
        w, h = rgb.size
        scale = 256 / max(w, h)
        scaled_w = min(256, max(1, round(w * scale)))
        scaled_h = min(256, max(1, round(h * scale)))
        arr = np.asarray(rgb.resize((scaled_w, scaled_h), Image.Resampling.BILINEAR),
                         dtype=np.float32) / 255
    pad_x = (256 - scaled_w) // 2
    pad_y = (256 - scaled_h) // 2
    # Same edge-clamped letterbox as the Android production engine.
    yy, xx = np.indices((256, 256), dtype=np.int32)
    yy = np.clip(yy - pad_y, 0, scaled_h - 1)
    xx = np.clip(xx - pad_x, 0, scaled_w - 1)
    input_chw = arr[yy, xx].transpose(2, 0, 1).copy()
    outputs = runtime.run(None, {"image": input_chw[None]})
    learned_mask = outputs[0][0, 0]
    diffuse = outputs[2][0]
    if not np.isfinite(learned_mask).all() or not np.isfinite(diffuse).all():
        raise AssertionError("Nonfinite real-photo network output")
    relevant = int((learned_mask >= .4).sum())
    alpha = np.clip((learned_mask - .1) / .8, 0, 1)
    adjusted = np.clip(input_chw + (diffuse-input_chw).clip(-1, 1) * alpha[None],
                       0, 1)
    # Android SpecularGlareEngine intentionally keeps alpha < 0.001 untouched;
    # FP32 sub-threshold rounding is not a visual edit and must not fail QA.
    corrected = alpha >= .001
    restored = np.where(corrected[None], adjusted, input_chw)
    delta = np.abs(restored-input_chw)
    max_change_outside = float(np.max(delta[:, ~corrected], initial=0.))
    changed = int((np.max(delta, axis=0) > .015).sum())
    metrics = {"photo": name, "source_pixels": [w, h],
               "glare_mask_pixels": relevant, "changed_pixels": changed,
               "changed_ratio": round(changed / 65536, 5),
               "mean_change_rgb": round(float(delta.mean()), 6),
               "unmasked_max_error": round(max_change_outside, 8)}
    print("REAL_PHOTO", json.dumps(metrics), flush=True)
    if max_change_outside > 1e-6:
        raise AssertionError("Android residual composition changed unmasked pixels")
    if relevant > 256*256*.75:
        raise AssertionError("Glare network selected dangerous full-frame mask")
    return (relevant >= 12 and changed >= 20)


def main():
    import numpy as np
    import onnxruntime as ort
    import torch

    print("SOURCE_COMMIT", SOURCE_COMMIT, flush=True)
    weights, weights_sha = download(BASE + WEIGHTS_FILE, MAX_WEIGHTS_BYTES)
    network, network_sha = download(BASE + NETWORK_FILE, 128_000)
    print("UPSTREAM_WEIGHTS", len(weights), weights_sha, flush=True)
    print("UPSTREAM_ARCHITECTURE_SHA256", network_sha, flush=True)
    if weights_sha != PINNED_WEIGHTS_SHA256 or network_sha != PINNED_NETWORK_SHA256:
        raise AssertionError("Changed upstream weights/source: refuse model conversion")

    if not 30_000 < len(weights) < 1_000_000:
        raise AssertionError("Weights are unexpectedly empty, too large, or a pointer")
    model = build_network_from_licensed_upstream(network.decode("utf-8"))
    # weights_only avoids executing arbitrary pickled Python objects.
    state = torch.load(io.BytesIO(weights), map_location="cpu", weights_only=True)
    model.load_state_dict(state, strict=True)
    model.eval()

    with tempfile.TemporaryDirectory(prefix="ml-glare-srnet-") as folder:
        root = pathlib.Path(folder)
        onnx_file = root / "specular_srnet_256.onnx"
        base, shiny, affected = synthetic_pair(256, 256)
        x = torch.from_numpy(shiny).unsqueeze(0)
        started = time.perf_counter()
        with torch.no_grad():
            expected = model(x)
        print("REAL_PYTORCH_INFERENCE_SECONDS", round(time.perf_counter()-started, 3))
        for i, output in enumerate(expected):
            if tuple(output.shape) != (1, 1 if i == 0 else 3, 256, 256):
                raise AssertionError(f"Invalid output shape {i}: {output.shape}")
            if not torch.isfinite(output).all():
                raise AssertionError("Nonfinite output from pretrained weights")

        torch.onnx.export(model, x, str(onnx_file), export_params=True,
                          opset_version=17, do_constant_folding=True,
                          input_names=["image"],
                          output_names=["highlight_mask", "specular", "diffuse"])
        import onnx
        onnx.checker.check_model(str(onnx_file))
        print("ONNX_BYTES", onnx_file.stat().st_size, flush=True)
        print("ONNX_SHA256", hashlib.sha256(onnx_file.read_bytes()).hexdigest(), flush=True)
        asset = pathlib.Path("app/src/main/assets/ai/specular_srnet_256.onnx.b64")
        bundle = base64.b64decode(asset.read_text().strip(), validate=True)
        if len(bundle) != BUNDLED_MODEL_BYTES or (
            hashlib.sha256(bundle).hexdigest() != BUNDLED_MODEL_SHA256
        ):
            raise AssertionError("Bundled Android model differs from the verified ONNX")
        # torch.onnx.export can produce byte-different protobuf node identifiers
        # across runs despite identical weights and numerical behavior. Verify
        # the shipped binary's fixed SHA and its actual inference parity below.
        if bundle != onnx_file.read_bytes():
            print("INFO_NONDETERMINISTIC_ONNX_SERIALIZATION", flush=True)
        print("BUNDLED_ANDROID_ONNX_SHA256", BUNDLED_MODEL_SHA256, flush=True)
        # One-time safe transfer into GitHub's binary blob API, no archived artifact,
        # release, or APK published. Normal CI runs do not dump model contents.
        if os.environ.get("ML_EXPORT_ONNX_BLOB") == "1":
            raw = base64.b64encode(onnx_file.read_bytes()).decode("ascii")
            print("ONNX_BASE64_BEGIN", flush=True)
            for i in range(0, len(raw), 76):
                print("ONNX_BASE64 " + raw[i:i+76], flush=True)
            print("ONNX_BASE64_END", flush=True)
        runtime = ort.InferenceSession(str(onnx_file), providers=["CPUExecutionProvider"])
        start = time.perf_counter()
        results = runtime.run(None, {"image": shiny[None]})
        print("ACTUAL_ONNX_CPU_SECONDS", round(time.perf_counter()-start, 3), flush=True)
        # Validate the actual app-shipped asset, not only the transient conversion.
        bundled_runtime = ort.InferenceSession(bundle, providers=["CPUExecutionProvider"])
        actual_bundle = bundled_runtime.run(None, {"image": shiny[None]})
        if any(np.max(np.abs(left-right)) > 0.0001
               for left, right in zip(actual_bundle, results)):
            raise AssertionError("Android-bundled ONNX produces a different output")
        for pytorch_output, onnx_output in zip(expected, results):
            err = float(np.max(np.abs(pytorch_output.detach().numpy() - onnx_output)))
            print("ONNX_PARITY_MAX_ABS", round(err, 7), flush=True)
            if not np.isfinite(onnx_output).all() or err > 0.003:
                raise AssertionError("ONNX export changed model behavior")
        pred = np.clip(results[2][0], 0, 1)
        glare_mae_before = float(np.mean(np.abs(shiny[:, affected]-base[:, affected])))
        glare_mae_after = float(np.mean(np.abs(pred[:, affected]-base[:, affected])))
        clean = ~affected
        clean_mae_before = float(np.mean(np.abs(shiny[:, clean]-base[:, clean])))
        clean_mae_after = float(np.mean(np.abs(pred[:, clean]-base[:, clean])))
        print("SYNTHETIC_GLARE_MAE_BEFORE", round(glare_mae_before, 5), flush=True)
        print("SYNTHETIC_GLARE_MAE_AFTER", round(glare_mae_after, 5), flush=True)
        print("CLEAN_AREA_MAE_BEFORE", round(clean_mae_before, 5), flush=True)
        print("CLEAN_AREA_MAE_AFTER", round(clean_mae_after, 5), flush=True)
        print("PRETRAINED_OUTPUT_MEAN_CHANGE", round(float(np.abs(pred-shiny).mean()), 5), flush=True)

        if np.abs(pred-shiny).mean() < 1e-4:
            raise AssertionError("Model output is identity / placebo")
        if glare_mae_after >= glare_mae_before:
            raise AssertionError("Pretrained model did not actually improve specular highlight test")
        # Poor preservation of unaffected details must be investigated even
        # when the glare reconstruction seems visually plausible.
        if clean_mae_after > .22:
            raise AssertionError("Unacceptable damage to unhighlighted image details")
        actual = [test_real_photo(bundled_runtime, name, filename, blob)
                  for name, (filename, blob) in REAL_SAMPLES.items()]
        if sum(actual) < 2:
            raise AssertionError("SRNet fails to make localized corrections on real photos")
        print("REAL_SRNET_GENUINE_SPECULAR_RESTORATION_PASS (Linux CPU; Android QA pending)",
              flush=True)


if __name__ == "__main__":
    main()
