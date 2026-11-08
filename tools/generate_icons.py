#!/usr/bin/env python3
"""Derive Android launcher resources from the user's unchanged favicon.png.

Requires Pillow only when regenerating. Normal Android builds use the committed
PNG/XML resources and do not need Python. The source PNG is never redrawn.
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
source = Image.open(ROOT / "favicon.png").convert("RGBA")
background = source.getpixel((min(5, source.width - 1), source.height // 2))
if background[3] != 255:
    raise SystemExit("Expected an opaque favicon background")
color = "#%02X%02X%02X" % background[:3]
(RES / "values/icon_background.xml").write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
    f'    <color name="ic_launcher_background">{color}</color>\n</resources>\n',
    encoding="utf-8",
)
for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    folder = RES / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    icon = source.resize((size, size), Image.Resampling.LANCZOS)
    icon.save(folder / "ic_launcher.png", optimize=True)
    icon.save(folder / "ic_launcher_round.png", optimize=True)
# Adaptive icons have a 108dp canvas and a 66dp central safe zone. Keep the
# complete supplied mark inside that zone; never crop the star's points.
for density, canvas in {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}.items():
    mark_size = round(canvas * 66 / 108)
    mark = source.resize((mark_size, mark_size), Image.Resampling.LANCZOS)
    layer = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    offset = (canvas - mark_size) // 2
    layer.alpha_composite(mark, (offset, offset))
    layer.save(RES / f"mipmap-{density}/ic_launcher_foreground.png", optimize=True)
adaptive = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
'''
for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
    (RES / "mipmap-anydpi-v26" / name).write_text(adaptive, encoding="utf-8")
print(f"Generated launcher resources from the exact favicon; background {color}")
