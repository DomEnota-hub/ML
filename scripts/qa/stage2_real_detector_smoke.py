#!/usr/bin/env python3
"""Live, weight-backed EfficientDet-Lite0 smoke on Linux CPU.

This is NOT an Android device test and is NOT a MobileSAM/LaMa accuracy test.
Network is used only by CI to fetch publicly licensed weights and upstream test
photos; MediaPipe receives local images and never uploads their pixels.
"""
import hashlib
import json
import pathlib
import resource
import tempfile
import time
import urllib.request

MODEL_URL = ("https://storage.googleapis.com/mediapipe-models/object_detector/"
             "efficientdet_lite0/float32/1/efficientdet_lite0.tflite")
EXPECTED_SHA256 = "40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58"
EXPECTED_SIZE = 13_836_895
SOURCE = ("https://raw.githubusercontent.com/google-ai-edge/mediapipe/master/"
          "mediapipe/tasks/testdata/vision/")


def download(url: str, path: pathlib.Path, maximum_bytes: int) -> dict:
    digest = hashlib.sha256()
    total = 0
    req = urllib.request.Request(url, headers={"User-Agent": "ML-Stage2-QA/1.0"})
    with urllib.request.urlopen(req, timeout=75) as response, path.open("wb") as out:
        if response.status != 200:
            raise RuntimeError(f"Unexpected download response {response.status}")
        while True:
            data = response.read(128 * 1024)
            if not data:
                break
            total += len(data)
            if total > maximum_bytes:
                raise RuntimeError("Download exceeds verified size cap")
            digest.update(data)
            out.write(data)
    return {"bytes": total, "sha256": digest.hexdigest()}


def main():
    import mediapipe as mp
    from PIL import Image

    with tempfile.TemporaryDirectory(prefix="ml-stage2-real-detector-") as directory:
        root = pathlib.Path(directory)
        model = root / "efficientdet_lite0.tflite"
        meta = download(MODEL_URL, model, 16_000_000)
        print("MODEL", json.dumps(meta), flush=True)
        if meta != {"bytes": EXPECTED_SIZE, "sha256": EXPECTED_SHA256}:
            raise AssertionError("Official detector bytes do not match Android SHA-256 pin")

        pose = root / "pose.jpg"
        burger = root / "burger.jpg"
        print("IMAGE pose", download(SOURCE + "pose.jpg", pose, 10_000_000), flush=True)
        print("IMAGE burger", download(SOURCE + "burger.jpg", burger, 10_000_000), flush=True)
        with Image.open(pose) as photo:
            rgb = photo.convert("RGB")
            width, height = rgb.size
            collage = Image.new("RGB", (width * 2, height), "white")
            collage.paste(rgb, (0, 0))
            collage.paste(rgb, (width, 0))
            pair = root / "pair.jpg"
            collage.save(pair, quality=94)

        options = mp.tasks.vision.ObjectDetectorOptions(
            base_options=mp.tasks.BaseOptions(model_asset_path=str(model)),
            running_mode=mp.tasks.vision.RunningMode.IMAGE,
            category_allowlist=["person"],
            score_threshold=0.35,
            max_results=12,
        )
        with mp.tasks.vision.ObjectDetector.create_from_options(options) as detector:
            results = {}
            for name, file in [("no_person", burger), ("one_person", pose),
                               ("two_person_collage", pair)]:
                start = time.perf_counter()
                image = mp.Image.create_from_file(str(file))
                found = detector.detect(image)
                elapsed = time.perf_counter() - start
                entries = []
                for item in found.detections:
                    for category in item.categories:
                        if category.category_name == "person":
                            rect = item.bounding_box
                            entries.append({
                                "confidence": round(float(category.score), 4),
                                "box": [int(rect.origin_x), int(rect.origin_y),
                                        int(rect.width), int(rect.height)],
                            })
                results[name] = {"people": len(entries), "boxes": entries,
                                 "cpu_seconds": round(elapsed, 3)}
                print("DETECTION", name, json.dumps(results[name]), flush=True)
        memory = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
        print("PEAK_RSS_KiB", memory, flush=True)
        if results["no_person"]["people"] != 0:
            raise AssertionError("Negative test falsely detected a person")
        if results["one_person"]["people"] < 1:
            raise AssertionError("Official model failed to find person in upstream pose.jpg")
        if results["two_person_collage"]["people"] < 2:
            raise AssertionError("Two-person collage did not produce independent detections")
        print("LIVE_DETECTOR_WEIGHTS_PASS (Linux CPU only; Android + SAM + LaMa pending)", flush=True)


if __name__ == "__main__":
    main()
