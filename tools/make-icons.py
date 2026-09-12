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
SS = 8

# Counter-clockwise, which leans a notehead's right end up. The Android vector
# writes this as android:rotation="-22", because that one is clockwise.
HEAD_TILT = 22  # supersample, then downscale: PIL has no antialiased polygon fill


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
    pen.rounded_rectangle(box(27, 40, 51, 68), radius=3 * k, fill=SLEEVE)
    pen.ellipse(box(35.1, 50.1, 42.9, 57.9), fill=LABEL)

    # A BEAMED PAIR, not a single quaver with a flag. The flag was tried and
    # it is a hairline curl: at the ~12px a home-screen icon gives this note it
    # thinned to nothing and left a stem with a blob on it. A beam is a solid
    # bar, and two heads say "music" where one says "a shape".
    #
    # THE HEADS ARE TILTED. Every notehead in engraved music is an ellipse
    # leaning with its right end up; an upright one is a shape nobody has seen
    # on a stave, and that is what made the old one look wrong without being
    # obviously wrong.
    for cx, cy in ((60.5, 65.5), (72.5, 62.5)):
        head = Image.new("RGBA", image.size, (0, 0, 0, 0))
        ImageDraw.Draw(head).ellipse(box(cx - 5.6, cy - 4.1, cx + 5.6, cy + 4.1), fill=NOTE)
        image.alpha_composite(head.rotate(HEAD_TILT, resample=Image.BICUBIC,
                                          center=(cx * k, cy * k)))
    pen = ImageDraw.Draw(image)

    # Stems rise from the RIGHT of each head, which is where they go on notes
    # sitting below the middle line.
    pen.rectangle(box(64.2, 41, 66.8, 65.5), fill=NOTE)
    pen.rectangle(box(76.2, 38, 78.8, 62.5), fill=NOTE)

    # The beam, sloping with the two heads.
    pen.polygon(
        [(x * k, y * k) for x, y in ((64.2, 41), (78.8, 38), (78.8, 43.5), (64.2, 46.5))],
        fill=NOTE
    )

    return image.resize((size, size), Image.LANCZOS)


if __name__ == "__main__":
    import os
    here = os.path.dirname(os.path.abspath(__file__))
    root = os.path.join(here, "..")
    out = os.path.join(root, "app", "src", "main", "assets", "web", "icons")
    for name, size in [
        ("apple-touch-icon.png", 180),
        ("icon-192.png", 192),
        ("icon-512.png", 512),
        ("favicon-32.png", 32),
    ]:
        path = os.path.join(out, name)
        draw(size).save(path)
        print("wrote", os.path.relpath(path, root))

    # The project page uses the same picture, from the same numbers, for the
    # same reason the two platforms do.
    pages = os.path.join(root, "docs")
    if os.path.isdir(pages):
        for name, size in [("icon.png", 512), ("favicon.png", 32)]:
            path = os.path.join(pages, name)
            draw(size).save(path)
            print("wrote", os.path.relpath(path, root))
