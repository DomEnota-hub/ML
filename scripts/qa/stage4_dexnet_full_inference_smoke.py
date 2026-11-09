#!/usr/bin/env python3
"""Genuine DExNet + VGG19 trained inference and Android-size ONNX feasibility gate.

Upstream 2025 IEEE PAMI research checkpoint & Apache-2.0 project:
https://github.com/jjhuangcs/DExNet at pinned SOURCE_REV.
The published checkpoint is from an external, public Google Drive file.
The pretrained VGG19 IMAGENET1K_V1 weights are required by the upstream
evaluation code for its 1,472 hypercolumn channels. We must not substitute
zeros/random features, pretend inference is real, or publish any weights.
This research workflow never distributes binaries, artifacts, APKs or models.
"""
from __future__ import annotations
import hashlib
import importlib.util
import io
import json
import os
import pathlib
import resource
import subprocess
import sys
import tempfile
import time
import types
import urllib.request

SOURCE_REV = "da949e2291ea758a191fc0c93990767348e6e4ab"
DNET_ID = "1jkhCvyrQAQZtLaxdDWDkkSOF0NZ12aJp"
DNET_BYTES = 118_442_981
DNET_SHA256 = "b58316c3cb339c0b334b83d5855446d19e5d57c49f88bc3b529b0da54498bcd3"
IMAGES = {
    "pose": (
        "https://storage.googleapis.com/mediapipe-assets/tasks/testdata/vision/pose.jpg"
        "?generation=1782185208547605",
        "c8a830ed683c0276d713dd5aeda28f415f10cd6291972084a40d0d8b934ed62b",
    ),
    "burger": (
        "https://storage.googleapis.com/mediapipe-assets/tasks/testdata/vision/burger.jpg"
        "?generation=1782184424868164",
        "97c15bbbf3cf3615063b1031c85d669de55839f59262bbe145d15ca75b36ecbf",
    ),
}
WIDTH = 128


def sha256(path: pathlib.Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def download_fixture(url: str, filename: pathlib.Path, expected: str) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "MediaLoader-DExNet-QA/1"})
    with urllib.request.urlopen(request, timeout=90) as response, filename.open("wb") as out:
        while block := response.read(65_536):
            out.write(block)
            if out.tell() > 10_000_000:
                raise RuntimeError("Fixture too large")
    if sha256(filename) != expected:
        raise AssertionError("Fixture SHA256 changed: " + filename.name)


def run(*args, cwd: pathlib.Path | None = None):
    subprocess.run(args, check=True, cwd=cwd, stdout=subprocess.DEVNULL)


def load_upstream(root: pathlib.Path):
    # Import ONLY the pinned upstream model architecture. Avoid imports of
    # upstream optimizer/training scripts and their external side effects.
    sys.modules["models"] = types.ModuleType("models")
    sys.modules["models"].__path__ = [str(root / "models")]
    sys.modules["models.arch"] = types.ModuleType("models.arch")
    sys.modules["models.arch"].__path__ = [str(root / "models/arch")]

    def load(name: str, filename: str):
        location = root / filename
        spec = importlib.util.spec_from_file_location(name, location)
        module = importlib.util.module_from_spec(spec)
        sys.modules[name] = module
        assert spec.loader is not None
        spec.loader.exec_module(module)
        return module

    load("models.arch.invNet", "models/arch/invNet.py")
    return load("models.arch.DexNet", "models/arch/DexNet.py").DExNet_expand


def benchmark_quality(target, pred):
    import numpy as np
    pred = np.clip(pred, 0, 1)
    mae = float(np.abs(pred - target).mean())
    mse = float(((pred - target) ** 2).mean())
    return {"mae": round(mae, 5), "psnr": round(-10 * np.log10(max(mse, 1e-10)), 3)}


def main():
    import gdown
    import numpy as np
    from PIL import Image, ImageFilter
    import torch
    from torch import nn
    import torch.nn.functional as F
    from torchvision.models import vgg19, VGG19_Weights

    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    with tempfile.TemporaryDirectory(prefix="ml-real-dexnet-") as tmp:
        root = pathlib.Path(tmp)
        source = root / "source"
        run("git", "clone", "-q", "--no-checkout", "https://github.com/jjhuangcs/DExNet.git", str(source))
        run("git", "checkout", "-q", SOURCE_REV, cwd=source)
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source).decode().strip()
        if revision != SOURCE_REV:
            raise AssertionError("Unpinned source revision")
        print("OFFICIAL_SOURCE_COMMIT", revision, flush=True)

        weights_path = root / "DExNet.pt"
        if not gdown.download(id=DNET_ID, output=str(weights_path), quiet=False):
            raise RuntimeError("Cannot download author's public pretrained checkpoint")
        if weights_path.stat().st_size != DNET_BYTES or sha256(weights_path) != DNET_SHA256:
            raise AssertionError("Real DExNet checkpoint SHA/bytes changed")
        state = torch.load(str(weights_path), weights_only=True, map_location="cpu")
        if not isinstance(state, dict) or not isinstance(state.get("weight"), dict):
            raise AssertionError("No proper pretrained network state")
        print("REAL_DEXNET_WEIGHTS_VERIFIED", DNET_SHA256, flush=True)

        arch = load_upstream(source)
        network = arch(1475, 3)
        network.load_state_dict(state["weight"], strict=True)
        network.eval()
        print("REAL_DEXNET_STRICT_WEIGHTS_LOADED",
              sum(p.numel() for p in network.parameters()), flush=True)

        t0 = time.perf_counter()
        vgg = vgg19(weights=VGG19_Weights.IMAGENET1K_V1).features[:30]
        vgg.eval()
        print("REQUIRED_VGG19_PRETRAINED_SECONDS",
              round(time.perf_counter()-t0, 2), flush=True)

        class CompleteModel(nn.Module):
            def __init__(self, vgg, network):
                super().__init__()
                self.vgg = vgg
                self.network = network

            def forward(self, rgb):
                feat = []
                x = rgb
                for index, layer in enumerate(self.vgg):
                    x = layer(x)
                    if (index + 1) in (2, 7, 12, 21, 30):
                        feat.append(F.interpolate(x, size=rgb.shape[2:],
                                                  mode="bilinear", align_corners=False))
                if sum(x.shape[1] for x in feat) != 1472:
                    raise AssertionError("Missing real VGG19 hypercolumn channels")
                hyper = torch.cat((rgb, *feat), dim=1)
                transmission, reflection, residual, aux = self.network(rgb, hyper)
                return transmission[-1], reflection[-1]

        model = CompleteModel(vgg, network).eval()
        for name, (url, expected) in IMAGES.items():
            download_fixture(url, root / (name + ".jpg"), expected)
        def rgb(name):
            with Image.open(root / (name + ".jpg")) as im:
                pixels = np.asarray(im.convert("RGB").resize((WIDTH, WIDTH),
                                 Image.Resampling.BILINEAR), dtype=np.float32) / 255.0
            return pixels
        target = rgb("pose")
        reflected = rgb("burger")
        scenes = {"clean_no_reflection": target, "clean_burger": reflected}
        truth = {"clean_no_reflection": target, "clean_burger": reflected}
        composites = []
        for scene_name, background, reflection in (
            ("pose_scene", target, reflected),
            ("burger_scene", reflected, target),
        ):
            soft_blurred = np.asarray(
                Image.fromarray((reflection * 255).astype(np.uint8)).filter(
                    ImageFilter.GaussianBlur(radius=3.0)),
                dtype=np.float32) / 255
            shifted = np.roll(soft_blurred, (3, 2), axis=(0, 1))
            ghost = .65 * soft_blurred + .35 * shifted
            for reflectance in (.08, .15, .25, .35):
                name = scene_name + "_sharp_" + str(int(reflectance*100))
                scenes[name] = np.clip((1-reflectance)*background +
                                       reflectance*reflection, 0, 1)
                truth[name] = background
                composites.append(name)
            for reflectance in (.15, .35):
                for kind, physical in (("blur", soft_blurred), ("ghost", ghost)):
                    name = scene_name + "_" + kind + "_" + str(int(reflectance*100))
                    scenes[name] = np.clip((1-reflectance)*background +
                                           reflectance*physical, 0, 1)
                    truth[name] = background
                    composites.append(name)
        # Keep canonical 15% mix for ONNX input, independent of matrix names.
        scenes["reflection_15_percent"] = scenes["pose_scene_sharp_15"]
        truth["reflection_15_percent"] = target
        scores = {}
        for name, scene in scenes.items():
            tensor = torch.from_numpy(np.transpose(scene, (2, 0, 1))[None].copy())
            start = time.perf_counter()
            with torch.no_grad():
                transmission, reflected_output = model(tensor)
            seconds = round(time.perf_counter()-start, 3)
            if tuple(transmission.shape) != (1, 3, WIDTH, WIDTH):
                raise AssertionError("Unexpected DExNet output shape")
            predicted = np.transpose(transmission[0].numpy(), (1, 2, 0))
            if not np.isfinite(predicted).all():
                raise AssertionError("DExNet yielded nonfinite outputs")
            metrics = {
                "input": benchmark_quality(truth[name], scene),
                "transmission": benchmark_quality(truth[name], predicted),
                "cpu_seconds": seconds,
            }
            scores[name] = metrics
            print("GENUINE_DEXNET_SCENE", name, json.dumps(metrics), flush=True)

        # Repeatable exporting of full REAL weights, including VGG19 features.
        # A standalone network that expects 1,475-channel fake user input does
        # NOT count as a usable mobile on-device model.
        import onnx
        import onnxruntime as ort
        onnxpath = root / "complete_dexnet_vgg19.onnx"
        input_tensor = torch.from_numpy(np.transpose(scenes["reflection_15_percent"],
                                                     (2, 0, 1))[None].copy())
        started = time.perf_counter()
        with torch.no_grad():
            baseline = model(input_tensor)[0].numpy()
            torch.onnx.export(model, input_tensor, str(onnxpath),
                              opset_version=17, do_constant_folding=True,
                              input_names=["image"],
                              output_names=["transmission", "reflection"],
                              export_params=True)
        print("COMPLETE_MODEL_ONNX_BYTES", onnxpath.stat().st_size,
              "seconds", round(time.perf_counter()-started, 2), flush=True)
        print("COMPLETE_MODEL_ONNX_SHA256", sha256(onnxpath), flush=True)
        if onnxpath.stat().st_size > 700 * 1024 * 1024:
            raise AssertionError("Full VGG19+DExNet model too large for offline Android budget")
        onnx.checker.check_model(str(onnxpath))
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        session = ort.InferenceSession(str(onnxpath), options, providers=["CPUExecutionProvider"])
        started = time.perf_counter()
        output = session.run(None, {"image": input_tensor.numpy()})[0]
        max_error = float(np.abs(output - baseline).max())
        print("COMPLETE_ONNX_PARITY_MAX_ABS", round(max_error, 6),
              "cpu_inference_s", round(time.perf_counter()-started, 2), flush=True)
        if max_error > .01 or not np.isfinite(output).all():
            raise AssertionError("Full export differs from PyTorch pretrained inference")
        print("PEAK_RSS_KIB", resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
              flush=True)
        # Measure ORT in a fresh process without holding the PyTorch weights,
        # VGG19 and transient ONNX export graph in memory simultaneously.
        child = subprocess.run([sys.executable, __file__, "--ort-resource-only",
                                str(onnxpath)], check=False, capture_output=True,
                               text=True, timeout=120)
        print(child.stdout.strip(), flush=True)
        if child.returncode != 0:
            print("ORT_STANDALONE_STDERR", child.stderr[-7000:], flush=True)
            raise RuntimeError("Standalone CPU ONNX resource test returned failure")

        # Fail honestly when synthetic benchmark worsens. Do not alter
        # the scene or threshold merely to generate a green result.
        improved = [key for key in composites
                    if scores[key]["transmission"]["mae"] <
                    scores[key]["input"]["mae"]]
        degraded = sorted(
            ((key, round(scores[key]["transmission"]["mae"] -
                         scores[key]["input"]["mae"], 5)) for key in composites),
            key=lambda x: x[1], reverse=True)
        summary = {"paired_scenes": len(composites),
                   "improved_scenes": len(improved),
                   "improvement_fraction": round(len(improved)/len(composites), 3),
                   "worst_degradation": degraded[:5],
                   "clean_pose_mae": scores["clean_no_reflection"]["transmission"]["mae"],
                   "clean_burger_mae": scores["clean_burger"]["transmission"]["mae"]}
        print("DEXNET_DIVERSE_QUALITY_MATRIX", json.dumps(summary), flush=True)
        if len(improved) < .65 * len(composites):
            raise AssertionError("DExNet does not improve a sufficient majority of paired scene mixes")
        if scores["clean_no_reflection"]["transmission"]["mae"] > .08 or (
                scores["clean_burger"]["transmission"]["mae"] > .08):
            raise AssertionError("DExNet corrupts an originally reflection-free image")
        if degraded[0][1] > .045:
            raise AssertionError("DExNet materially degrades difficult reflection composites")
        print("GENUINE_DEXNET_MODEL_AND_ONNX_RESEARCH_PASS; "
              "Android/licensing/device QA pending", flush=True)


def ort_resource_child(onnxpath: pathlib.Path):
    import numpy as np
    import onnxruntime as ort
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 2
    started = time.perf_counter()
    session = ort.InferenceSession(str(onnxpath), opts,
                                   providers=["CPUExecutionProvider"])
    model_load = round(time.perf_counter() - started, 3)
    rgb = np.full((1, 3, WIDTH, WIDTH), .4, dtype=np.float32)
    started = time.perf_counter()
    output = session.run(None, {session.get_inputs()[0].name: rgb})
    duration = round(time.perf_counter()-started, 3)
    if any(not np.isfinite(z).all() for z in output):
        raise RuntimeError("Standalone ONNX produces malformed output")
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    print("ORT_ONLY_CPU_RESOURCE", json.dumps({
        "model_bytes": onnxpath.stat().st_size,
        "model_loading_seconds": model_load,
        "model_inference_seconds": duration,
        "peak_rss_kib": peak,
        "peak_rss_mib": round(peak/1024, 1),
        "architecture": "linux_x86_64; Android ARM64 requires separate QA",
    }), flush=True)
    if peak > 800_000:
        raise RuntimeError("Full pretrained model exceeds 781 MiB ORT-only RAM budget")


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--ort-resource-only":
        ort_resource_child(pathlib.Path(sys.argv[2]))
    elif len(sys.argv) == 1:
        main()
    else:
        raise SystemExit("Unrecognized QA mode")
