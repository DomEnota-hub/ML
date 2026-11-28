#!/usr/bin/env python3
"""Dependency cache namespace, not a test-result or build admission key."""
import hashlib
import os
from pathlib import Path
import re
import subprocess

ROOT=Path(__file__).resolve().parents[2]
# Verified QA-233 version-only transition: CI 267 warm inputs -> CI 268 cold.
# The exact full legacy hash is the condition, not a broad cache prefix.
MIGRATION={
    '4837697dce3f94a792450a608e588e5d8707c902e7772bd9aa6e3e690466a8cb':
    '9bef626813c4e65d2e0247598473aad8cf966d53510b26339b34aa67d86e905e'
}

def normalized(path, data):
    if path != 'app/build.gradle':
        return data
    data, codes = re.subn(rb"(?m)^(        versionCode )[0-9]+([ \t]*\r?)$", rb"\g<1>RELEASE_CODE\g<2>", data)
    data, names = re.subn(rb"(?m)^(        versionName )'[0-9]+\.[0-9]+\.[0-9]+'([ \t]*\r?)$", rb"\g<1>RELEASE_NAME\g<2>", data)
    if (codes,names)!=(1,1):
        raise ValueError('Expected one base release code/name; review cache fingerprint')
    return data

def fingerprint(root, paths):
    h=hashlib.sha256()
    selected=sorted(p for p in paths if p.endswith(('.gradle','.gradle.kts')) or Path(p).name in ('gradle.properties','gradle-wrapper.properties','libs.versions.toml'))
    if 'app/build.gradle' not in selected:
        raise ValueError('Missing app/build.gradle')
    for path in selected:
        h.update(path.encode('utf-8')+b'\0')
        h.update(hashlib.sha256(normalized(path,(root/path).read_bytes())).digest())
    return h.hexdigest()

def migration(legacy):
    if not re.fullmatch('[a-f0-9]{64}',legacy):
        raise ValueError('Invalid legacy cache hash')
    return MIGRATION.get(legacy,legacy)

if __name__=='__main__':
    files=subprocess.check_output(['git','ls-files','-z'],cwd=ROOT).decode('utf-8').split('\0')
    legacy=os.environ['LEGACY_HASH']
    deps=fingerprint(ROOT,files)
    with open(os.environ['GITHUB_OUTPUT'],'a') as out:
        out.write(f'deps={deps}\nlegacy={legacy}\nmigration={migration(legacy)}\n')
