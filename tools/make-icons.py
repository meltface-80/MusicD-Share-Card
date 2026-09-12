#!/usr/bin/env python3
"""
The app icon, rendered from one SVG and emitted for both platforms.

    python3 tools/make-icons.py --render    # SVG  -> the two masters (needs a browser)
    python3 tools/make-icons.py             # masters -> every icon in the repo

TWO STAGES, AND THE REASON IS CI. tools/icon/source.svg is the icon: it carries
gradients, a drop shadow and a glow, and only a real SVG engine draws those the
way the author meant. Chromium is that engine here. But a check that re-rendered
the SVG could not compare the result to what is committed — a different Chromium
draws antialiasing differently, and the check would fail for a reason that has
nothing to do with the icon. So the browser stage is run by hand and its two
outputs are COMMITTED (tools/icon/artwork.png, tools/icon/backdrop.png), and
everything after it is Pillow arithmetic that any machine reproduces exactly.
tools/check-icons.py checks that second half, in CI.

    source.svg --[--render, a browser, by hand]--> artwork.png + backdrop.png
                                                        |
                                       [this script, Pillow]--> 16 icons

CHANGING THE ICON means replacing source.svg, running BOTH stages and committing
everything that moves. Stopping after the first leaves sixteen icons drawn from
the old picture, and nothing in this repo reads them — only a launcher and a
Home Screen do, neither of which is here.

WHAT IT EMITS

  app/src/main/assets/web/icons/   the PWA and apple-touch icons
  docs/                            the project page's logo and favicon
  app/src/main/res/mipmap-*/       the two layers of the Android adaptive icon

WHY THE TILE IS THROWN AWAY. source.svg draws its artwork on a rounded tile,
inset from the edge — an icon as a picture of an icon. Every platform here masks
its own shape out of a full-bleed square, so what is emitted is the backdrop
carried to all four edges with the artwork over it, and iOS, the launcher and
the browser each round it however they round things. The tile's rounding and its
glass edge are deliberately not in the output; they would be cut off by those
masks, or worse, cut off just short of them.
"""

import os
import subprocess
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
SOURCE = os.path.join(HERE, "icon", "source.svg")
ARTWORK = os.path.join(HERE, "icon", "artwork.png")
BACKDROP = os.path.join(HERE, "icon", "backdrop.png")

# The masters are rendered at the SVG's own coordinate system, which is twice
# the largest icon anything here asks for.
MASTER = 1024

# The artwork alone: the only direct-child <rect> elements of the <svg> are the
# tile and its glass edge, so this selector names them without touching the
# author's file. Do not "simplify" it by editing source.svg — the SVG is theirs.
ARTWORK_CSS = "svg > rect { display: none }"

# The backdrop alone, squared off and carried to every edge. The tile rect is
# the first child; CSS geometry properties move and unround it in place.
BACKDROP_CSS = (
    "svg > g, svg > path, svg > rect + rect { display: none }\n"
    "svg > rect:first-of-type { x: 0; y: 0; width: 1024px; height: 1024px; rx: 0 }"
)

CHROMES = (
    os.environ.get("CHROME"),
    "/opt/pw-browsers/chromium-1194/chrome-linux/chrome",
    "/usr/bin/chromium",
    "/usr/bin/chromium-browser",
    "/usr/bin/google-chrome",
)

# Where the artwork's outermost pixel lands on Android's 108dp canvas.
#
# A LAUNCHER MASK IS A CIRCLE AS OFTEN AS IT IS A SQUARE, so the measurement
# that matters is a RADIUS from the artwork's own centre and not the width of
# its bounding box: the arcs sit top-right and the sleeve bottom-left, and a box
# around both is far bigger than the artwork actually is. Google guarantees the
# middle 66dp of the 108dp canvas, so 34 is a shade inside the worst case, and
# the corners no longer decide how big the icon is allowed to be.
SAFE_RADIUS = 34 / 108

# Alpha below this is the shadow and the glow rather than the artwork. Sizing to
# the glow would shrink everything else to make room for a blur.
SOLID = 40


def _chrome():
    for path in CHROMES:
        if path and os.path.exists(path):
            return path
    raise SystemExit(
        "no Chromium found — set CHROME=/path/to/chrome. This stage needs a real\n"
        "SVG engine; the committed masters are there so nothing else does."
    )


def _render(css, size=MASTER):
    """One rasterisation of source.svg, with `css` applied over it."""
    page = os.path.join(HERE, "icon", ".render.html")
    shot = os.path.join(HERE, "icon", ".render.png")
    with open(page, "w") as out:
        out.write(
            "<!doctype html><meta charset=utf-8>\n<style>\n"
            "html, body { margin: 0; padding: 0; background: transparent; overflow: hidden }\n"
            f"svg {{ display: block; width: {size}px; height: {size}px }}\n"
            f"{css}\n</style>\n" + open(SOURCE).read()
        )
    try:
        subprocess.run(
            [_chrome(), "--headless", "--no-sandbox", "--disable-gpu", "--hide-scrollbars",
             "--force-device-scale-factor=1", "--default-background-color=00000000",
             # Headless spends some of the window on chrome of its own, so ask
             # for more than is wanted and cut the square out of the top-left.
             f"--window-size={size},{size + 240}", f"--screenshot={shot}", "file://" + page],
            check=True, capture_output=True,
        )
        image = Image.open(shot).convert("RGBA")
        image.load()
        if image.width < size or image.height < size:
            raise SystemExit(f"the browser drew {image.size}, which is short of {size}")
        return image.crop((0, 0, size, size))
    finally:
        for path in (page, shot):
            if os.path.exists(path):
                os.remove(path)


def render_masters():
    for css, path in ((ARTWORK_CSS, ARTWORK), (BACKDROP_CSS, BACKDROP)):
        _render(css).save(path, optimize=True)
        print("rendered", os.path.relpath(path, ROOT))


_masters = {}


def _master(path):
    if path not in _masters:
        image = Image.open(path).convert("RGBA")
        image.load()
        _masters[path] = image
    return _masters[path]


def _artwork():
    """The artwork, its centre, and how far from that centre it reaches."""
    art = _master(ARTWORK)
    alpha = art.split()[3]
    box = alpha.getbbox()
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    pixels = alpha.load()
    far = 0.0
    for y in range(box[1], box[3]):
        for x in range(box[0], box[2]):
            if pixels[x, y] >= SOLID:
                far = max(far, ((x - cx) ** 2 + (y - cy) ** 2) ** 0.5)
    return art, cx, cy, far


def square(size):
    """
    A finished full-bleed icon: the backdrop to all four edges, artwork over it.

    The artwork keeps the place and the size the SVG gives it, because nothing
    masks these as hard as a launcher does — iOS rounds the corners off and a
    browser draws the square as it is.
    """
    canvas = _master(BACKDROP).resize((size, size), Image.LANCZOS)
    canvas.alpha_composite(_master(ARTWORK).resize((size, size), Image.LANCZOS))
    return canvas.convert("RGB")


def adaptive(size):
    """
    The two layers of the Android adaptive icon, on a 108dp canvas `size` across.

    The foreground is the artwork and nothing else — genuinely transparent
    around it, which is what the SVG buys over a photograph of an icon: there is
    no rectangle to hide and so no seam to hide it with. The background is the
    backdrop, full bleed, and a launcher may slide one against the other for
    parallax without exposing anything.
    """
    art, cx, cy, far = _artwork()
    scale = SAFE_RADIUS * size / far
    side = max(1, round(art.width * scale))
    scaled = art.resize((side, side), Image.LANCZOS)

    foreground = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    foreground.paste(scaled, (round(size / 2 - cx * scale), round(size / 2 - cy * scale)))
    return foreground, _master(BACKDROP).resize((size, size), Image.LANCZOS).convert("RGB")


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
        "app/src/main/assets/web/icons/favicon-32.png": square(32),
        # The project page shows the same picture, from the same masters.
        "docs/icon.png": square(512),
        "docs/favicon.png": square(32),
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
    if "--render" in sys.argv:
        render_masters()
    for path, image in generate().items():
        full = os.path.join(ROOT, path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        image.save(full, optimize=True)
        print("wrote", path)
