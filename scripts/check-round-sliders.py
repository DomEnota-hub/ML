#!/usr/bin/env python3
"""CI-only guard for ordinary sliders; specialized crop/playhead handles remain independent."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
ui = root / 'app/src/main/java/com/domenota/medialoader/ui'
shared = ui / 'components/AppSlider.kt'
source = shared.read_text()
assert 'background(color, CircleShape)' in source, 'Shared thumb must remain round'
assert 'heightIn(min = 48.dp)' in source, 'Shared slider needs a 48dp interaction target'
assert 'strokeWidth = 4.dp.toPx()' in source, 'Shared track must remain thin'
assert 'thumb = {' in source and 'track = {' in source, 'Do not restore the default Material handle/track'
for path in ui.rglob('*.kt'):
    if path == shared:
        continue
    text = path.read_text()
    if re.search(r'\bSlider\(', text):
        assert re.search(r'import com\.domenota\.medialoader\.ui\.components\.(AppSlider|EditorSlider) as Slider', text), \
            f'Ordinary slider bypasses shared round style: {path.relative_to(root)}'
print('Round slider style guard passed')
