#!/usr/bin/env python3
"""No-release signed QA handoff: 2 part ZIPs for one APK + 1 exact-SHA source ZIP.

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
    if len(apk) < 10_000 or len(apk) > 2 * (MAX_FILE - 100_000):
        raise RuntimeError("Signed APK cannot fit into exactly two 100MB archives")
    args.out.mkdir(parents=True)
    halves = [apk[:(len(apk)+1)//2], apk[(len(apk)+1)//2:]]
    written = []
    for i, content in enumerate(halves, start=1):
        output = args.out / f"ML_{args.version}_QA_ARM64_part{i}.zip"
        internal_name = f"ML_{args.version}_QA_ARM64.apk.{i:03d}"
        instructions = (
            "Извлеките *.apk.001 и *.apk.002 из ОБОИХ архивов. "
            "Объедините части по порядку 001 затем 002 в единый APK. "
            "Проверьте SHA-256 итогового файла перед установкой. "
            "Пример Linux: cat *.apk.001 *.apk.002 > MediaLoader_QA.apk"
        )
        meta = {
            "version": args.version,
            "source_commit": args.commit,
            "original_apk_sha256": digest(apk),
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
            raise RuntimeError("Part exceeds Google Drive limit")
        written.append(output)
    with zipfile.ZipFile(written[0]) as first, zipfile.ZipFile(written[1]) as second:
        assert first.testzip() is None and second.testzip() is None
        assert first.read(f"ML_{args.version}_QA_ARM64.apk.001") + second.read(
            f"ML_{args.version}_QA_ARM64.apk.002") == apk

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
        "apk_sha256": digest(apk), "source_commit": args.commit,
        "version": args.version,
        "files": [{ "name": file.name, "bytes": file.stat().st_size,
                    "sha256": digest(file.read_bytes())} for file in written + [archive]],
    }
    print("VERIFIED_QA_HANDOFF", json.dumps(output, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
