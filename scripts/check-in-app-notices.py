#!/usr/bin/env python3
from pathlib import Path
import re

root = Path("app/src/main/java")
allowed = root / "com/domenota/medialoader/data/share/InstagramShareActivity.kt"
matches = []
for path in root.rglob("*.kt"):
    text = path.read_text(encoding="utf-8")
    if re.search(r"\bToast\s*\.|import\s+android\.widget\.Toast", text) and path != allowed:
        matches.append(str(path))
if matches:
    raise SystemExit("Unexpected in-app Android Toast: " + ", ".join(matches))
assert allowed.read_text(encoding="utf-8").count("Toast.makeText(") == 2, "Review external share error fallback explicitly"
print("In-app Toast guard: PASS (external share receiver only)")
