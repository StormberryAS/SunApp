#!/usr/bin/env python3
"""Regenerate app/src/main/res/drawable/ic_stormberry_logo.xml.

The symbol half of the Stormberry lockup exists only as a raster: BrandAssets'
stormberry-symbol-*.svg and stormberry-logo-*.svg are a PNG wrapped in an <image>
tag, not outlines. Only stormberry-wordmark-*.svg is real vector. Tracing the
full lockup from the raster master gets both halves and, more usefully, the
official symbol-to-wordmark spacing in one pass, instead of recombining a traced
symbol with the wordmark outlines by hand.

It has to become a vector one way or another: this app ships no raster resources
at all, because generated density PNGs are a source of build nondeterminism.

    pip install potracer pillow numpy cairosvg
    python3 tools/build-stormberry-logo.py \
        ../../../BrandAssets/full/stormberry-logo-white.png \
        app/src/main/res/drawable/ic_stormberry_logo.xml

Paths are relative to the android/ directory. The master is not in this
repository; it lives in the private BrandAssets tree alongside it.

Two lint checks, both errors in this build, shape the output: VectorPath rejects
a pathData over 3000 characters, and VectorRaster rejects an intrinsic dimension
over 200dp. Hence one <path> per outer contour rather than one path for the whole
mark, and integer coordinates.

The script prints an intersection-over-union against the master at full
resolution. It has been above 0.98; treat a drop as a regression.
"""
import io
import os
import sys

import cairosvg
import numpy as np
from PIL import Image
import potrace

if len(sys.argv) != 3:
    sys.exit(__doc__)
SRC, OUT = sys.argv[1], sys.argv[2]

if not os.path.isfile(SRC):
    sys.exit(
        f'master not found: {SRC}\n'
        'Expected the private BrandAssets tree as a sibling of this checkout, i.e.\n'
        '  <...>/StormberryAS/BrandAssets/full/stormberry-logo-white.png\n'
        '  <...>/StormberryAS/GitHub/UsernameGenerator/   <- this repository\n'
        'which from android/ is ../../../BrandAssets/full/stormberry-logo-white.png.'
    )

im = Image.open(SRC).convert('RGBA')
mask = np.array(im)[..., 3] > 128
ys, xs = np.nonzero(mask)
crop = mask[ys.min():ys.max() + 1, xs.min():xs.max() + 1]
H, W = crop.shape
print(f'crop {W}x{H}', file=sys.stderr)

curves = list(potrace.Bitmap(~crop).trace(turdsize=30, alphamax=1.0, opticurve=True, opttolerance=0.2))

def poly(c):
    return np.array([(p.x, p.y) for p in c.decomposition_points], dtype=float)

polys = [poly(c) for c in curves]

def area(p):
    x, y = p[:, 0], p[:, 1]
    return abs(np.dot(x, np.roll(y, -1)) - np.dot(y, np.roll(x, -1))) / 2

def contains(p, pt):
    x, y = pt
    xs_, ys_ = p[:, 0], p[:, 1]
    xj, yj = np.roll(xs_, -1), np.roll(ys_, -1)
    cross = ((ys_ > y) != (yj > y)) & (x < (xj - xs_) * (y - ys_) / np.where(yj != ys_, yj - ys_, 1e-12) + xs_)
    return bool(cross.sum() % 2)

areas = [area(p) for p in polys]
parent = [None] * len(curves)
for i, p in enumerate(polys):
    pt = (curves[i].start_point.x, curves[i].start_point.y)
    best = None
    for j, q in enumerate(polys):
        if i != j and contains(q, pt) and (best is None or areas[j] < areas[best]):
            best = j
    parent[i] = best

def depth(i):
    d = 0
    while parent[i] is not None:
        i = parent[i]; d += 1
    return d

outers = [i for i in range(len(curves)) if depth(i) % 2 == 0]
holes = {i: [j for j in range(len(curves)) if parent[j] == i and depth(j) % 2 == 1] for i in outers}
print(f'{len(curves)} contours -> {len(outers)} outers, {sum(len(v) for v in holes.values())} holes', file=sys.stderr)

def f(v):
    return str(int(round(v)))

def d_of(c):
    sp = c.start_point
    out = [f'M{f(sp.x)},{f(sp.y)}']
    for seg in c:
        if seg.is_corner:
            out.append(f'L{f(seg.c.x)},{f(seg.c.y)}L{f(seg.end_point.x)},{f(seg.end_point.y)}')
        else:
            out.append(f'C{f(seg.c1.x)},{f(seg.c1.y)} {f(seg.c2.x)},{f(seg.c2.y)} {f(seg.end_point.x)},{f(seg.end_point.y)}')
    out.append('Z')
    return ''.join(out)

# Left-to-right, so the XML reads in the order the eye does.
outers.sort(key=lambda i: polys[i][:, 0].min())
groups = [d_of(curves[i]) + ''.join(d_of(curves[j]) for j in holes[i]) for i in outers]
longest = max(len(g) for g in groups)
print(f'paths={len(groups)} longest={longest} total={sum(len(g) for g in groups)}', file=sys.stderr)
assert longest < 3000, f'a path is {longest} chars, lint VectorPath fails at 3000'

# The intrinsic size is the viewport scaled down by a whole number, so the official
# proportions survive exactly. 16 is the smallest power of two that keeps both
# dimensions of this master under the 200dp that lint's VectorRaster check wants.
INTRINSIC_DIVISOR = 16
iw, ih = W / INTRINSIC_DIVISOR, H / INTRINSIC_DIVISOR
assert max(iw, ih) <= 200, f'intrinsic {iw}x{ih}dp exceeds 200dp; raise INTRINSIC_DIVISOR'

def dp(v):
    return f'{v:g}'

paths = '\n'.join(
    f'    <path\n        android:fillColor="#FFFFFFFF"\n        android:fillType="evenOdd"\n        android:pathData="{g}" />'
    for g in groups)

open(OUT, 'w').write(f'''<?xml version="1.0" encoding="utf-8"?>
<!--
  The Stormberry lockup, traced from BrandAssets/full/stormberry-logo-white.png at
  alpha > 128, which is the threshold that drops the master's drop shadow.

  A vector and not a PNG on purpose: this module ships no raster resources at all,
  which is what lets the APK be byte-reproducible. See vectorDrawables.generatedDensities()
  in app/build.gradle.kts, and the androidResources comment next to it.

  Generated, not hand-written. Regenerate with tools/build-stormberry-logo.py.
  One path per outer contour, each carrying its own counters, because lint's
  VectorPath check rejects a single pathData over 3000 characters. Coordinates are
  integers on a {W}-unit viewport: one unit is 0.04dp at the size the footer draws
  this, comfortably under a device pixel.

  The viewport is the ink bounding box of the master, and the intrinsic size below
  is that viewport over {INTRINSIC_DIVISOR}, so the official proportions are exact and both
  dimensions stay under the 200dp that lint's VectorRaster check wants. Callers set
  the height and let the width follow.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{dp(iw)}dp"
    android:height="{dp(ih)}dp"
    android:viewportWidth="{W}"
    android:viewportHeight="{H}">
{paths}
</vector>
''')
print('wrote', OUT, file=sys.stderr)

# Fidelity control: rasterise what we just emitted and compare to the source mask.
svg_paths = ''.join(f'<path fill="#fff" fill-rule="evenodd" d="{g}"/>' for g in groups)
svg = f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}"><rect width="{W}" height="{H}" fill="#000"/>{svg_paths}</svg>'
buf = cairosvg.svg2png(bytestring=svg.encode(), output_width=W, output_height=H)
tr = np.array(Image.open(io.BytesIO(buf)).convert('L')) > 128
inter = (crop & tr).sum(); union = (crop | tr).sum()
print(f'native-res IoU vs master: {inter/union:.5f}  (differing px {int((crop ^ tr).sum())} of {int(crop.sum())})', file=sys.stderr)
