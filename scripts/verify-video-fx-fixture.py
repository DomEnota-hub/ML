"""Reject corrupt synthetic inputs before reporting Media3 failures."""
from pathlib import Path
import base64, subprocess, tempfile
assets = Path(__file__).resolve().parents[1] / "app/src/androidTest/assets"
for source in sorted(assets.glob("fx_*_h264_160x90_800ms.base64")):
    with tempfile.TemporaryDirectory() as directory:
        fixture = Path(directory) / "fixture.mp4"
        fixture.write_bytes(base64.b64decode(source.read_text().strip(), validate=True))
        subprocess.run(["ffmpeg", "-v", "error", "-xerror", "-i", str(fixture), "-f", "null", "-"], check=True)
        print(source.name, "fully decoded without errors")
