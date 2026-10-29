"""Keep bundled editor fonts small; retain Latin, Cyrillic, punctuation and layout features.

Requires fonttools. Run explicitly when adding original fonts, not during the Android build.
All redistributed derivatives receive distinct internal family names; original notices remain.
"""
from pathlib import Path
from fontTools import subset
from fontTools.ttLib import TTFont

root = Path(__file__).resolve().parents[1] / "app/src/main/assets/text-fonts"
for path in root.glob("*.ttf"):
    options = subset.Options()
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.name_legacy = True
    options.layout_features = ["*"]
    font = TTFont(path)
    subsetter = subset.Subsetter(options=options)
    codepoints = list(range(0x530)) + list(range(0x1C80, 0x1C90))
    codepoints += list(range(0x1E00, 0x1F00)) + list(range(0x2000, 0x2150))
    subsetter.populate(unicodes=codepoints)
    subsetter.subset(font)
    family, style = path.stem.rsplit("-", 1)
    alias = {
        "OpenSans": "ML Humanist Sans", "NotoSans": "ML Neutral Sans",
        "NotoSerif": "ML Neutral Serif", "LiberationSans": "ML Classic Sans",
        "LiberationSerif": "ML Classic Serif", "LiberationMono": "ML Classic Mono",
        "DejaVuSerif": "ML Book Serif", "DejaVuSans": "ML Light Sans",
    }[family]
    names = {1: alias, 2: style, 3: f"{alias} {style} v1", 4: f"{alias} {style}",
             6: f"{alias.replace(' ', '')}-{style}", 16: alias, 17: style}
    for record in font["name"].names:
        if record.nameID in names:
            record.string = names[record.nameID].encode(record.getEncoding())
    font.save(path)
