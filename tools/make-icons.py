#!/usr/bin/env python3
"""
The app icon, cut from one picture and emitted for both platforms.

THE SOURCE IS A RENDER, NOT GEOMETRY. Until 0.26.0 this script DREW the icon —
a sleeve and a beamed pair of notes, from the same numbers as a vector in
ic_launcher_foreground.xml, and the two were kept in step by hand. The icon is
now a supplied 3D render (tools/icon/source.png): gradients, bevels, a soft
shadow and a glow, none of which a handful of rounded rectangles can express and
none of which an Android vector can hold. So the drawing is gone, the render is
the single source, and this script only ever crops and scales it.

    python3 tools/make-icons.py

WHAT IT EMITS

  app/src/main/assets/web/icons/   the PWA and apple-touch icons
  docs/                            the project page's logo and favicon
  app/src/main/res/mipmap-*/       the two layers of the Android adaptive icon

WHY THE ANDROID ONE IS TWO BITMAPS AND NOT THE RENDER. A launcher masks the
middle of a 108dp canvas — roughly a 72dp circle — and only the middle 66dp is
guaranteed to survive. The render's artwork spans very nearly the whole of its
tile, so handing the tile over full-bleed loses the sleeve's left edge and the
arcs' right. The artwork is therefore scaled to sit inside that safe zone, and
the space around it is the tile's own background carried outwards by repeating
its border — which is how the FOREGROUND layer's rectangle and the BACKGROUND
layer's field meet with no seam: at the join they are the same pixels.
"""

import os
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
SOURCE = os.path.join(HERE, "icon", "source.png")

# The render is a photograph of a tile: the tile floats on a grey backdrop with
# a drop shadow, and neither belongs in an icon. These are the tile's own edges,
# measured off the source, pulled in by RIM so the crop starts inside the bevel
# highlight that runs along the top and the dark line along the bottom. A rim
# left in would draw a faint square outline across the finished icon, because
# everything outside the crop is made by repeating the crop's border.
TILE = (126, 82, 1163, 1122)   # left, top, right, bottom
RIM = 26

# The tile's corner radius, measured off the source, and how far inside its arc
# a repaired corner is allowed to sample.
#
# TRIMMING THE RIM IS NOT ENOUGH ON ITS OWN, and the first version of this only
# did that: a square crop inset 26px from a rounded rect still has its four
# corners OUTSIDE the rounding, so each one carried a wedge of the render's grey
# backdrop — and because everything beyond the crop is made by repeating its
# border, each wedge was then smeared out to the edge of the icon as a notch.
# Insetting far enough to clear the arcs is not an option either: the artwork
# very nearly fills the tile, so there is no spare margin to give up. The
# corners are repaired instead, by pulling each one back onto the arc.
CORNER = 225
CORNER_INSET = 35

# The artwork itself — sleeve, note and arcs — measured off the source by
# looking for saturated or near-white pixels. It is what gets centred, and what
# the safe-zone fractions below are fractions OF. Its own soft shadow and glow
# fall outside it and are carried along by the crop.
ART = (206, 236, 1088, 871)

# How wide the artwork is drawn, as a fraction of the finished square.
#
# WEB is close to full because iOS and the browser only round the corners off.
# ANDROID is not: 62/108 keeps the artwork inside the 66dp a launcher mask
# guarantees, whatever shape that launcher's mask happens to be.
WEB = 0.80
SMALL = 0.90      # a favicon is 32px; the same margin at that size is mush
ANDROID = 62 / 108


def _repair(tile, x0, y0):
    """Replace each corner's wedge of backdrop with the tile beside it."""
    left, top, right, bottom = TILE
    radius = CORNER - CORNER_INSET
    pixels = tile.load()
    for sx, sy, dx, dy in (
        (left + CORNER, top + CORNER, -1, -1),
        (right - CORNER, top + CORNER, 1, -1),
        (left + CORNER, bottom - CORNER, -1, 1),
        (right - CORNER, bottom - CORNER, 1, 1),
    ):
        cx, cy = sx - x0, sy - y0
        for y in range(tile.height):
            if (y - cy) * dy <= 0:
                continue
            for x in range(tile.width):
                if (x - cx) * dx <= 0:
                    continue
                ox, oy = x - cx, y - cy
                far = (ox * ox + oy * oy) ** 0.5
                if far <= radius:
                    continue
                k = radius / far
                pixels[x, y] = pixels[
                    min(tile.width - 1, max(0, round(cx + ox * k))),
                    min(tile.height - 1, max(0, round(cy + oy * k))),
                ]
    return tile


def _crop():
    """The tile, square, with its rim trimmed off and its corners repaired."""
    left, top, right, bottom = TILE
    side = min(right - left, bottom - top) - 2 * RIM
    cx, cy = (left + right) / 2, (top + bottom) / 2
    x0, y0 = round(cx - side / 2), round(cy - side / 2)
    tile = Image.open(SOURCE).convert("RGB").crop((x0, y0, x0 + side, y0 + side))
    art = (ART[0] - x0, ART[1] - y0, ART[2] - x0, ART[3] - y0)
    return _repair(tile, x0, y0), art


def _place(size, fraction):
    """Where the tile lands on a `size` square, with the artwork centred."""
    tile, art = _crop()
    scale = fraction * size / (art[2] - art[0])
    side = max(1, round(tile.width * scale))
    scaled = tile.resize((side, side), Image.LANCZOS)
    ox = round(size / 2 - (art[0] + art[2]) / 2 * scale)
    oy = round(size / 2 - (art[1] + art[3]) / 2 * scale)
    return scaled, ox, oy


def _extend(canvas, tile, ox, oy):
    """
    Carry the tile's border out to the edges of `canvas`.

    Nearest-edge replication, which is the one extension that CANNOT show a
    seam: every pixel touching the tile is a copy of the tile pixel beside it.
    The border here is flat near-black, so what it actually looks like is the
    tile simply being larger.
    """
    w, h = tile.size
    size = canvas.width
    top = tile.crop((0, 0, w, 1))
    bottom = tile.crop((0, h - 1, w, h))
    left = tile.crop((0, 0, 1, h))
    right = tile.crop((w - 1, 0, w, h))

    if oy > 0:
        canvas.paste(top.resize((w, oy), Image.NEAREST), (ox, 0))
    below = size - (oy + h)
    if below > 0:
        canvas.paste(bottom.resize((w, below), Image.NEAREST), (ox, oy + h))
    if ox > 0:
        canvas.paste(left.resize((ox, h), Image.NEAREST), (0, oy))
    beyond = size - (ox + w)
    if beyond > 0:
        canvas.paste(right.resize((beyond, h), Image.NEAREST), (ox + w, oy))

    # The four corners, each the single pixel nearest to it.
    for cx, cy, px, py in (
        (0, 0, 0, 0), (ox + w, 0, w - 1, 0),
        (0, oy + h, 0, h - 1), (ox + w, oy + h, w - 1, h - 1),
    ):
        cw = ox if cx == 0 else size - (ox + w)
        ch = oy if cy == 0 else size - (oy + h)
        if cw > 0 and ch > 0:
            canvas.paste(Image.new("RGB", (cw, ch), tile.getpixel((px, py))), (cx, cy))
    return canvas


def square(size, fraction=WEB):
    """The finished icon, full-bleed: the tile, centred, carried to the edges."""
    tile, ox, oy = _place(size, fraction)
    canvas = Image.new("RGB", (size, size))
    canvas.paste(tile, (ox, oy))
    return _extend(canvas, tile, ox, oy)


def adaptive(size):
    """
    The two layers of the Android adaptive icon.

    The foreground is the tile and nothing else — transparent everywhere the
    tile is not — so a launcher that shifts the layers against each other for
    parallax shifts the artwork, not the whole picture. The background is the
    same field the full-bleed version uses, with the artwork replaced by a
    vertical blend of the tile's own top and bottom rows: it is only ever seen
    a pixel or two at a time, at the edge of the shifted foreground, and it has
    to match what is beside it rather than show a second copy of the note.
    """
    tile, ox, oy = _place(size, ANDROID)
    foreground = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    foreground.paste(tile.convert("RGBA"), (ox, oy))

    w, h = tile.size
    blend = Image.new("RGB", (w, h))
    top, bottom = tile.crop((0, 0, w, 1)), tile.crop((0, h - 1, w, h))
    for y in range(h):
        row = Image.blend(top, bottom, y / max(1, h - 1))
        blend.paste(row, (0, y))
    background = Image.new("RGB", (size, size))
    background.paste(blend, (ox, oy))
    return foreground, _extend(background, tile, ox, oy)


def generate():
    """
    Every icon this repo holds, keyed by its path from the repo root.

    One table, used both by the writer below and by tools/check-icons.py, so
    that an output nobody remembered to regenerate is a failing check rather
    than two platforms quietly showing different pictures.
    """
    out = {
        "app/src/main/assets/web/icons/apple-touch-icon.png": square(180),
        "app/src/main/assets/web/icons/icon-192.png": square(192),
        "app/src/main/assets/web/icons/icon-512.png": square(512),
        "app/src/main/assets/web/icons/favicon-32.png": square(32, SMALL),
        # The project page shows the same picture, cut from the same render.
        "docs/icon.png": square(512),
        "docs/favicon.png": square(32, SMALL),
    }
    # 108dp, at each density Android asks for.
    for bucket, px in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216),
                       ("xxhdpi", 324), ("xxxhdpi", 432)):
        foreground, background = adaptive(px)
        res = "app/src/main/res/mipmap-" + bucket + "/"
        out[res + "ic_launcher_foreground.png"] = foreground
        out[res + "ic_launcher_background.png"] = background
    return out


if __name__ == "__main__":
    for path, image in generate().items():
        full = os.path.join(ROOT, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        image.save(full, optimize=True)
        print("wrote", path)
