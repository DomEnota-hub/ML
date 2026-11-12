#!/usr/bin/env python3
"""Guard direct violet literals. Semantic palette and rendering tests cover the theme branches.
Scan application Kotlin UI and XML resources; every permitted literal has a documented reason.
This is not an image-based proof of every runtime state.
"""
import colorsys
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
PATTERN = re.compile(r"0x(?:[0-9a-fA-F]{2})?([0-9a-fA-F]{6})\b|#(?:[0-9a-fA-F]{2})?([0-9a-fA-F]{6})\b")
ALLOWED = {
    'app/src/main/java/com/domenota/medialoader/ui/theme/Theme.kt': {'7036CB', '7A4DFF', 'BB9CFF', '762DFF', '9B7BFF', '6544B2', '321D77'},  # Original light theme and explicit original Shazam-result exception.
    'app/src/main/java/com/domenota/medialoader/ui/theme/SettingsIconPalette.kt': {'6C48B6', '42243B', '6F36BB', '8746C4'},  # Light-only setting icon foregrounds; dark branches use approved gold.
    'app/src/main/java/com/domenota/medialoader/ui/components/VideoSongSearchDialog.kt': {'9573FF', '8456EC'},  # Light-only recognition loading controls, dark uses current gold.
    'app/src/main/java/com/domenota/medialoader/ui/components/SourceIcon.kt': {'8064CB', '6044A8', '7046E8', '24113F'},  # Source brand gradients plus explicit light-only generic/RUTUBE colors.
    'app/src/main/java/com/domenota/medialoader/ui/components/BulkProgressCard.kt': {'8456EC'},  # Light-only existing progress tint.
    'app/src/main/java/com/domenota/medialoader/ui/components/LibraryUiStyle.kt': {'201B29'},  # Existing light-only text neutral.
    'app/src/main/java/com/domenota/medialoader/ui/screens/VideoOverlayPanel.kt': {'B78AFF'},  # User-selectable text/overlay paint, not UI chrome.
    'app/src/main/java/com/domenota/medialoader/ui/screens/PhotoLevelNavigation.kt': {'9870D6'},  # Semantic HSL channel hue; changing it would mislabel purple color correction.
    'app/src/main/java/com/domenota/medialoader/ui/screens/PhotoDrawDialog.kt': {'914FDC'},  # User-selectable paint palette, not editor chrome.
    'app/src/main/java/com/domenota/medialoader/ui/screens/SourceBrowserScreen.kt': {'7638E8'},  # Light-only injected control; dark uses gold.
}

def violet(value):
    rgb = [int(value[i:i+2], 16) / 255 for i in (0, 2, 4)]
    hue, saturation, brightness = colorsys.rgb_to_hsv(*rgb)
    return 245 <= hue * 360 <= 315 and saturation > .16 and brightness > .14

paths = list((ROOT / "app/src/main/java/com/domenota/medialoader/ui").rglob("*.kt"))
paths += list((ROOT / "app/src/main/res").rglob("*.xml"))
violations = []
for path in paths:
    relative = path.relative_to(ROOT).as_posix()
    for number, line in enumerate(path.read_text().splitlines(), 1):
        for match in PATTERN.finditer(line):
            value = (match.group(1) or match.group(2)).upper()
            if violet(value) and value not in ALLOWED.get(relative, set()):
                violations.append(f"{relative}:{number}: unclassified violet #{value}")
if violations:
    raise SystemExit("\n".join(violations))
print(f"Checked {len(paths)} Kotlin UI/XML files; all direct violet literals classified.")
