#!/usr/bin/env python3
"""CI layout and version sanity; not a release approval."""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[2]

def validate_workflows(root, allowed):
    directory = root / '.github/workflows'
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError('Missing or aliased workflow directory')
    entries = list(directory.iterdir())
    actual = {p.name for p in entries}
    if actual != set(allowed) or any(not p.is_file() or p.is_symlink() for p in entries):
        raise ValueError(f'Unexpected workflow layout: added={sorted(actual-set(allowed))}, missing={sorted(set(allowed)-actual)}')

def validate_version(text):
    # Only literal base declarations; generated review/debug versionCode expressions are not the base release.
    codes = re.findall(r'(?m)^\s*versionCode\s+([0-9]+)[ \t]*$', text)
    names = re.findall(r"(?m)^\s*versionName\s+'([0-9]+\.[0-9]+\.[0-9]+)'[ \t]*$", text)
    if len(codes) != 1 or not 0 < int(codes[0]) <= 2100000000 or len(names) != 1:
        raise ValueError('Expected one valid literal release versionName and versionCode')
    return names[0], int(codes[0])

def main():
    allowed=json.loads((ROOT/'docs/ci/allowed-workflows.json').read_text())
    if not isinstance(allowed,list) or not allowed or len(allowed)!=len(set(allowed)):
        raise ValueError('Invalid workflow allowlist')
    validate_workflows(ROOT,allowed)
    version,code=validate_version((ROOT/'app/build.gradle').read_text())
    print(f'Workflow allowlist PASS ({len(allowed)}); version {version}/{code}')

if __name__=='__main__':main()
