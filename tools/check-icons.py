#!/usr/bin/env python3
"""
Every committed icon is what tools/make-icons.py builds from the committed masters.

WHAT THIS IS FOR. Sixteen PNGs are generated from two masters, and nothing else
in the repo reads any of them — a launcher and a Home Screen do, on a device
that is not here. So the two ways this drifts are both silent: somebody edits an
output by hand and the next run of the script puts it back, or somebody replaces
the source and regenerates only the half they were looking at, after which the
Android icon and the iOS one are different pictures. Neither shows up in a diff
anybody reads, and neither fails a build.

WHAT IT CANNOT CHECK is the stage above: whether the committed masters are what
today's Chromium draws from tools/icon/source.svg. Two versions of a browser
antialias differently, so re-rendering here would fail for a reason that has
nothing to do with the icon. That stage is run by hand, and this check is what
catches forgetting the stage AFTER it.

PIXELS, NOT BYTES. Two versions of Pillow compress a PNG differently for the
same image, so comparing file bytes would fail in CI for a reason that has
nothing to do with the icon.

    python3 tools/check-icons.py
"""

import os
import sys

from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "make_icons", os.path.join(os.path.dirname(os.path.abspath(__file__)), "make-icons.py")
)
make_icons = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(make_icons)

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")


def main():
    problems = []
    for path, expected in make_icons.generate().items():
        full = os.path.join(ROOT, path)
        if not os.path.exists(full):
            problems.append(f"{path}: missing — run tools/make-icons.py")
            continue
        committed = Image.open(full)
        committed.load()
        if committed.size != expected.size:
            problems.append(f"{path}: {committed.size}, expected {expected.size}")
        elif committed.convert(expected.mode).tobytes() != expected.tobytes():
            problems.append(f"{path}: differs from the source — run tools/make-icons.py")
        else:
            print("ok  ", path)

    if problems:
        print("\nThe committed icons are not what the source produces:\n")
        for problem in problems:
            print("  ", problem)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
