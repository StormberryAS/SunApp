#!/usr/bin/env python3
"""Render the store icon that Zapstore and F-Droid display.

WHY THIS EXISTS. The APK ships no raster icon at all: `vectorDrawables
.generatedDensities()` is empty and the launcher icon is an adaptive-icon XML,
because generated density PNGs are a source of build nondeterminism and this
build claims byte reproducibility. Android renders that XML happily. Store
front-ends do not: they look for a bitmap, find `res/BW.xml`, and show an empty
tile. That is exactly what Zapstore was displaying.

The fix is metadata rather than a change to the APK, so reproducibility is
untouched. `zsp` reads Fastlane metadata automatically for GitHub repositories,
and F-Droid reads the same tree, so one file serves both.

    pip install cairosvg pillow
    python3 tools/build-store-icon.py

Output: fastlane/metadata/android/en-US/images/icon.png at 512x512, which is
the size both stores expect.

The composition deliberately mirrors the adaptive icon rather than just scaling
favicon.svg to fill the square: same background colour, same inset, so the store
tile and the launcher icon are the same image.
"""
import io
import os

import cairosvg
from PIL import Image

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SVG = os.path.join(HERE, "favicon.svg")
OUT = os.path.join(HERE, "fastlane", "metadata", "android", "en-US", "images", "icon.png")

SIZE = 512
# The adaptive foreground draws the sun across roughly 64 of a 108 viewport (rays
# reach r=32 from centre), so it occupies about 59% of the tile. Matching that
# keeps the store tile and the launcher icon identical instead of merely similar.
MARK_FRACTION = 64 / 108
# res/values/colors.xml -> stormberry_icon_background (SunApp: #080C18)
BACKGROUND = (0x08, 0x0C, 0x18, 0xFF)


def main():
    mark_px = round(SIZE * MARK_FRACTION)
    png = cairosvg.svg2png(url=SVG, output_width=mark_px, output_height=mark_px)
    mark = Image.open(io.BytesIO(png)).convert("RGBA")

    canvas = Image.new("RGBA", (SIZE, SIZE), BACKGROUND)
    offset = ((SIZE - mark_px) // 2, (SIZE - mark_px) // 2)
    canvas.alpha_composite(mark, offset)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    canvas.save(OUT, "PNG", optimize=True)

    opaque = sum(1 for p in mark.convert("RGBA").tobytes()[3::4] if p > 0)
    print(f"wrote {OUT}")
    print(f"  {SIZE}x{SIZE}, mark {mark_px}px ({MARK_FRACTION:.0%} of the tile)")
    print(f"  mark has {opaque} opaque pixels; zero would mean the SVG failed to render")
    if opaque == 0:
        raise SystemExit("SVG rendered empty, refusing to ship a blank icon")


if __name__ == "__main__":
    main()
