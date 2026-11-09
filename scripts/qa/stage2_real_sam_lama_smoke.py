#!/usr/bin/env python3
"""Real EfficientDet -> MobileSAM -> LaMa smoke (Linux CPU, not Android QA).

Downloads SHA-pinned public models to a temporary directory. No weights, images
or APK are committed, published or uploaded; all work is local to CI.
"""
import hashlib
from pathlib import Path
import tempfile
import time
import urllib.request

import numpy as np
from PIL import Image
from stage2_real_detector_smoke import MODEL_URL, EXPECTED_SHA256, EXPECTED_SIZE, SAMPLES

MODELS = {
    "sam_encoder": (
        "https://huggingface.co/Acly/MobileSAM/resolve/0d3b403339b4674a82493d5e97964dd78089ddc8/mobile_sam_image_encoder.onnx?download=true",
        28_157_093, "580f5fb648ea1062c0aabc26217aed56921985f03f0cbbd852bba81d760cc749"),
    "sam_decoder": (
        "https://huggingface.co/Acly/MobileSAM/resolve/0d3b403339b4674a82493d5e97964dd78089ddc8/sam_mask_decoder_single.onnx?download=true",
        16_501_323, "93915fc7c993ab9d59ab8c9ccd3bce37f7509c81ab4150a74abd4d2abbd8570d"),
    "lama": (
        "https://huggingface.co/sapienkit/LaMa-ONNX/resolve/main/lama_fp32.onnx?download=true",
        None, "1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6"),
}


def fetch(url, destination, expected_length=None, expected_sha=None, cap=251_000_000):
    total = 0
    digest = hashlib.sha256()
    request = urllib.request.Request(url, headers={"User-Agent": "ML-Stage2-QA/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response:
        if response.status != 200:
            raise RuntimeError("Unexpected model source response")
        with open(destination, "wb") as output:
            while True:
                block = response.read(128 * 1024)
                if not block:
                    break
                total += len(block)
                if total > cap:
                    raise RuntimeError("Model exceeded download size cap")
                digest.update(block)
                output.write(block)
    sha = digest.hexdigest()
    if (expected_length is not None and total != expected_length) or (
            expected_sha is not None and sha != expected_sha):
        raise RuntimeError("Model SHA-256 or byte size mismatch: " + Path(destination).name)
    print("VERIFIED", Path(destination).name, total, sha, flush=True)


def main():
    import onnxruntime as ort
    import mediapipe as mp

    with tempfile.TemporaryDirectory(prefix="ml-stage2-actual-onnx-") as temp:
        root = Path(temp)
        pose_url, pose_sha = SAMPLES["pose.jpg"]
        pose_file = root / "pose.jpg"
        fetch(pose_url, pose_file, expected_sha=pose_sha, cap=10_000_000)
        original = Image.open(pose_file).convert("RGB")
        src_width, src_height = original.size
        scale = 1024 / max(src_width, src_height)
        width = int(src_width * scale + 0.5)
        height = int(src_height * scale + 0.5)
        rgb = np.asarray(original.resize((width, height), Image.Resampling.BILINEAR),
                         dtype=np.float32)
        model_file = root / "efficientdet_lite0.tflite"
        fetch(MODEL_URL, model_file, EXPECTED_SIZE, EXPECTED_SHA256, cap=16_000_000)
        options = mp.tasks.vision.ObjectDetectorOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=str(model_file)),
            running_mode=mp.tasks.vision.RunningMode.IMAGE,
            category_allowlist=["person"], score_threshold=.35, max_results=12)
        with mp.tasks.vision.ObjectDetector.create_from_options(options) as detector:
            detected = detector.detect(mp.Image.create_from_file(str(pose_file))).detections
        if not detected:
            raise AssertionError("Real EfficientDet returned no person")
        best = max(detected, key=lambda x: x.categories[0].score)
        box = best.bounding_box
        px = (box.origin_x + box.width * .5) / src_width
        py = (box.origin_y + box.height * .45) / src_height
        print("DETECTOR_PROMPT", round(px, 4), round(py, 4), flush=True)

        for name in ("sam_encoder", "sam_decoder"):
            url, expected_size, expected_sha = MODELS[name]
            fetch(url, root / (name + ".onnx"), expected_size, expected_sha)
        options_ort = ort.SessionOptions()
        options_ort.intra_op_num_threads = 2
        encoder = ort.InferenceSession(str(root / "sam_encoder.onnx"),
                                       options_ort, providers=["CPUExecutionProvider"])
        decoder = ort.InferenceSession(str(root / "sam_decoder.onnx"),
                                       options_ort, providers=["CPUExecutionProvider"])
        print("ENCODER_INPUT", [(x.name, x.shape) for x in encoder.get_inputs()], flush=True)
        print("DECODER_INPUTS", [(x.name, x.shape) for x in decoder.get_inputs()], flush=True)
        started = time.perf_counter()
        embeddings = encoder.run(None, {"input_image": rgb})[0]
        print("ENCODER", embeddings.shape, round(time.perf_counter() - started, 3), flush=True)
        assert tuple(embeddings.shape) == (1, 256, 64, 64)
        feeds = {
            "image_embeddings": embeddings,
            "point_coords": np.array([[[width * px, height * py]]], dtype=np.float32),
            "point_labels": np.array([[1.0]], dtype=np.float32),
            "mask_input": np.zeros((1, 1, 256, 256), dtype=np.float32),
            "has_mask_input": np.array([0.0], dtype=np.float32),
            "orig_im_size": np.array([height, width], dtype=np.float32),
        }
        started = time.perf_counter()
        logits = decoder.run(None, feeds)[0]
        print("DECODER", logits.shape, round(time.perf_counter() - started, 3), flush=True)
        assert tuple(logits.shape) == (1, 1, height, width)
        assert np.isfinite(logits).all(), "MobileSAM returned non-finite logits"
        selected = logits[0, 0] > 0
        proportion = float(selected.mean())
        print("REAL_MOBILE_SAM_MASK_RATIO", round(proportion, 5), flush=True)
        assert .001 < proportion < .80, "No valid MobileSAM mask"

        url, expected_size, expected_sha = MODELS["lama"]
        fetch(url, root / "lama.onnx", expected_size, expected_sha)
        lama = ort.InferenceSession(str(root / "lama.onnx"), options_ort,
                                    providers=["CPUExecutionProvider"])
        print("LAMA_INPUTS", [(x.name, x.shape) for x in lama.get_inputs()], flush=True)
        source = np.asarray(original.resize((512, 512), Image.Resampling.BILINEAR),
                            dtype=np.float32)
        rgb_chw = source.transpose(2, 0, 1)[None] / 255.0
        pil_mask = Image.fromarray((selected.astype(np.uint8) * 255), mode="L")
        mask = np.asarray(pil_mask.resize((512, 512), Image.Resampling.NEAREST),
                          dtype=np.float32)[None, None] / 255.0
        started = time.perf_counter()
        output = lama.run(None, {"image": rgb_chw, "mask": mask})[0]
        print("LAMA", output.shape, round(time.perf_counter() - started, 3), flush=True)
        assert tuple(output.shape) == (1, 3, 512, 512)
        assert np.isfinite(output).all()
        prediction = output[0].transpose(1, 2, 0).clip(0, 255)
        alpha = mask[0, 0][:, :, None]
        composite = source * (1 - alpha) + prediction * alpha
        assert np.array_equal(composite[mask[0, 0] == 0],
                              source[mask[0, 0] == 0]), "Unmasked pixels changed"
        marked = mask[0, 0] > 0
        delta = float(np.mean(np.abs(composite[marked] - source[marked])))
        print("ACTUAL_LAMA_MARKED_MEAN_DELTA", round(delta, 3), flush=True)
        assert delta > 1.0, "No meaningful inpainting within mask"
        print("LIVE_SAM_LAMA_WEIGHTS_PASS (Linux CPU; visual Xiaomi QA pending)", flush=True)


if __name__ == "__main__":
    main()
