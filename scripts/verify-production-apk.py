"""Unsigned release preflight. Never signs, publishes or changes an APK."""
import hashlib
import re
import subprocess
import sys
import zipfile
from pathlib import Path

apk, aapt = map(Path, sys.argv[1:3])
assert apk.is_file() and 0 < apk.stat().st_size <= 130_000_000, "APK missing or exceeds 130MB"
badging = subprocess.check_output([str(aapt), "dump", "badging", str(apk)], text=True)
identity = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
assert identity and identity.groups() == ("com.domenota.medialoader", "183", "1.7.122"), "Wrong production identity/version"
with zipfile.ZipFile(apk) as archive:
    abis = {name.split('/')[1] for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
assert abis == {"arm64-v8a"}, f"Unexpected production ABIs: {abis}"
print(f"Unsigned production preflight: {identity.groups()}, ABI arm64-v8a, {apk.stat().st_size} bytes")
print("Unsigned APK SHA-256:", hashlib.sha256(apk.read_bytes()).hexdigest())
print("Official certificate and user acceptance remain separate release gates.")
