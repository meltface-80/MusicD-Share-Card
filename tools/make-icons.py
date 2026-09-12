#!/usr/bin/env python3
"""
The app icon, drawn once and emitted for both platforms.

WHY A SCRIPT AND NOT FOUR HAND-MADE PNGS. iOS will not take an SVG for an
apple-touch-icon and Android wants a vector, so the same picture has to exist in
two forms. Drawing them from one description here is what stops them drifting
apart — which is the whole reason the geometry below is also the geometry in
ic_launcher_foreground.xml, and why that file's paths are pasted from the
numbers in this one.

    python3 tools/make-icons.py

Coordinates are on Android's 108x108 adaptive-icon canvas. The launcher masks
that to roughly the middle 72, so nothing meaningful may stray outside 27..81.
"""

from PIL import Image, ImageDraw

BG = (22, 25, 29, 255)        # --surface
SLEEVE = (212, 160, 23, 255)  # --accent
LABEL = (22, 25, 29, 255)
NOTE = (232, 237, 242, 255)   # --text

CANVAS = 108.0
SS = 8  # supersample, then downscale: PIL has no antialiased polygon fill


def bezier(p0, p1, p2, p3, steps=48):
    """A cubic, sampled into points — PIL draws polygons, not curves."""
    out = []
    for i in range(steps + 1):
        t = i / steps
        u = 1 - t
        out.append((
            u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0],
            u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1],
        ))
    return out


def draw(size):
    s = size * SS
    k = s / CANVAS
    image = Image.new("RGBA", (s, s), BG)
    pen = ImageDraw.Draw(image)

    def box(x0, y0, x1, y1):
        return [x0 * k, y0 * k, x1 * k, y1 * k]

    # The sleeve, and a label so it reads as a record rather than a square.
    pen.rounded_rectangle(box(27, 38, 57, 70), radius=3 * k, fill=SLEEVE)
    pen.ellipse(box(37, 45, 47, 55), fill=LABEL)

    # The note: head, stem, flag. An eighth note rather than three lines of
    # text, which is what this said before and what nobody read as anything.
    pen.ellipse(box(60, 59.3, 73, 69.7), fill=NOTE)
    pen.rounded_rectangle(box(71, 39, 74.5, 65), radius=1.75 * k, fill=NOTE)

    flag = (
        bezier((74.5, 39), (79.5, 42), (81, 47.5), (78.5, 53)) +
        bezier((78.5, 53), (80, 47.5), (78, 44.5), (74.5, 45.5))
    )
    pen.polygon([(x * k, y * k) for x, y in flag], fill=NOTE)

    return image.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    import os
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(here, "..", "app", "src", "main", "assets", "web", "icons")
    for name, size in [
        ("apple-touch-icon.png", 180),
        ("icon-192.png", 192),
        ("icon-512.png", 512),
        ("favicon-32.png", 32),
    ]:
        path = os.path.join(out, name)
        draw(size).save(path)
        print("wrote", os.path.relpath(path, os.path.join(here, "..")))
