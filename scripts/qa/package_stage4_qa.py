#!/usr/bin/env python3
"""No-release QA handoff: 2 ZIP parts for APK + optional ONNX, 1 source ZIP.

Outputs ONLY in runner-local directory; GitHub Artifacts and Releases forbidden.
The dedicated Drive step must upload these three files and verify their hashes.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
import pathlib
import subprocess
import zipfile

MAX_FILE = 100_000_000


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--apk", type=pathlib.Path, required=True)
    p.add_argument("--model", type=pathlib.Path, required=True)
    p.add_argument("--out", type=pathlib.Path, required=True)
    p.add_argument("--version", required=True)
    p.add_argument("--commit", required=True)
    args = p.parse_args()
    if not args.commit or len(args.commit) != 40:
        raise ValueError("Commit must be a full SHA")
    if args.out.exists():
        raise RuntimeError("Refusing to overwrite existing handoff folder")
    apk = args.apk.read_bytes()
    with zipfile.ZipFile(io.BytesIO(apk)) as z:
        names = z.namelist()
        if "AndroidManifest.xml" not in names or not any(
            name.startswith("lib/arm64-v8a/") for name in names
        ):
            raise RuntimeError("Not a compiled ARM64 APK")
        assert z.testzip() is None
    model = args.model.read_bytes()
    pinned = "0898eefd440b3adfc13b32b49b14c94e1acbf9b5ad5270f4022513b891a90917"
    if len(model) != 91_341_128 or digest(model) != pinned:
        raise RuntimeError("ONNX SHA-256 or model length differs from the vetted DExNet")
    if len(apk) < 10_000:
        raise RuntimeError("Invalid compiled QA APK")
    instructions = (
        "Из двух архивов извлеките ML_*_QA_bundle.zip.001 и .002. "
        "Соедините по порядку в ML_QA_bundle.zip "
        "(Linux: cat *.zip.001 *.zip.002 > ML_QA_bundle.zip). "
        "Распакуйте ZIP. Установите APK. Затем откройте "
        "Настройки -> AI-модели -> DExNet -> Импортировать и укажите .onnx. "
        "Проверьте SHA-256; фото не передаются в сеть. "
        "Оценка качества и тест Xiaomi отдельно."
    )
    # Do not embed optional 91 MB weights into the base APK. Both archives
    # carry an APK and a separate model with a known pinned checksum.
    payload = io.BytesIO()
    with zipfile.ZipFile(payload, "w", compression=zipfile.ZIP_STORED) as combined:
        combined.writestr(f"ML_{args.version}_QA_ARM64.apk", apk)
        combined.writestr("dexnet_vgg19_reflection_128.onnx", model)
        combined.writestr("INSTALL_README.txt", instructions.encode("utf-8"))
    bundle = payload.getvalue()
    if len(bundle) > 2 * (MAX_FILE - 100_000):
        raise RuntimeError("Signed APK plus ONNX exceeds 2x100MB; optimize instead of removing model")
    args.out.mkdir(parents=True)
    halves = [bundle[:(len(bundle)+1)//2], bundle[(len(bundle)+1)//2:]]
    written = []
    for i, content in enumerate(halves, start=1):
        output = args.out / f"ML_{args.version}_QA_ARM64_part{i}.zip"
        internal_name = f"ML_{args.version}_QA_bundle.zip.{i:03d}"
        meta = {
            "version": args.version,
            "source_commit": args.commit,
            "original_apk_sha256": digest(apk),
            "model_sha256": digest(model),
            "qa_bundle_sha256": digest(bundle),
            "part": i,
            "part_sha256": digest(content),
            "instructions": instructions,
        }
        with zipfile.ZipFile(output, "w") as z:
            z.writestr(internal_name, content, compress_type=zipfile.ZIP_STORED)
            z.writestr("SHA256_README.json",
                       json.dumps(meta, indent=2, ensure_ascii=False),
                       compress_type=zipfile.ZIP_DEFLATED)
        if output.stat().st_size > MAX_FILE:
            raise RuntimeError("QA part exceeds Google Drive size limit")
        written.append(output)
    with zipfile.ZipFile(written[0]) as first, zipfile.ZipFile(written[1]) as second:
        assert first.testzip() is None and second.testzip() is None
        restored_bytes = first.read(f"ML_{args.version}_QA_bundle.zip.001") + second.read(
            f"ML_{args.version}_QA_bundle.zip.002")
        assert digest(restored_bytes) == digest(bundle)
        with zipfile.ZipFile(io.BytesIO(restored_bytes)) as restored:
            assert restored.testzip() is None
            assert digest(restored.read(f"ML_{args.version}_QA_ARM64.apk")) == digest(apk)
            assert digest(restored.read("dexnet_vgg19_reflection_128.onnx")) == digest(model)

    archive = args.out / f"ML_{args.version}_sources_{args.commit[:12]}.zip"
    source = subprocess.run(
        ["git", "archive", "--format=zip",
         f"--prefix=ML_{args.version}_sources/", args.commit],
        capture_output=True, check=True).stdout
    with zipfile.ZipFile(io.BytesIO(source)) as z:
        if z.testzip() is not None: raise RuntimeError("Broken source ZIP")
        if not any(n.endswith("app/build.gradle") for n in z.namelist()):
            raise RuntimeError("Source is incomplete")
        if any(n.endswith((".jks", ".keystore", ".p12", ".pem", ".key"))
               for n in z.namelist()):
            raise RuntimeError("Private signing key checked into source")
    archive.write_bytes(source)
    output = {
        "apk_sha256": digest(apk), "model_sha256": digest(model), "source_commit": args.commit,
        "version": args.version,
        "files": [{ "name": file.name, "bytes": file.stat().st_size,
                    "sha256": digest(file.read_bytes())} for file in written + [archive]],
    }
    print("VERIFIED_QA_HANDOFF", json.dumps(output, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
