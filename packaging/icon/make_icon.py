"""Builds the DiPlayDesktop app icon from glyph.png: the glyph in dark grey on a light grey squircle.

Run from this directory with Pillow and NumPy installed:  python make_icon.py
Writes ../diplaydesktop.ico (exe icon for jpackage), ../../src/main/resources/icons/diplaydesktop-N.png
(window icon), and for a future macOS build ../macos/diplaydesktop.icns and ../macos/diplaydesktop-1024.png
on Apple's macOS icon grid (an 824-pixel body on a 1024 canvas, with a soft drop shadow).
"""
from pathlib import Path

import numpy as np
from PIL import Image, ImageFilter

HERE = Path(__file__).resolve().parent
SIZE = 1024
SUPERSAMPLE = 4
# A light vertical gradient, lighter at the top as under the gear of iOS's Settings icon.
BACKGROUND_TOP = (226, 226, 231)
BACKGROUND_BOTTOM = (176, 176, 182)
GLYPH = (28, 28, 30)            # iOS label, dark
GLYPH_FRACTION = 0.60           # the glyph's longer side relative to the icon
SQUIRCLE_EXPONENT = 5.0         # superellipse close to Apple's continuous-corner icon shape


def squircle(size):
    big = size * SUPERSAMPLE
    centre = (big - 1) / 2.0
    y, x = np.mgrid[0:big, 0:big]
    u = np.abs((x - centre) / (big / 2.0))
    v = np.abs((y - centre) / (big / 2.0))
    inside = (u ** SQUIRCLE_EXPONENT + v ** SQUIRCLE_EXPONENT) <= 1.0
    return Image.fromarray((inside * 255).astype(np.uint8), "L").resize((size, size), Image.LANCZOS)


def gradient(size):
    t = np.linspace(0.0, 1.0, size)[:, None, None]
    top = np.array(BACKGROUND_TOP, dtype=float)
    bottom = np.array(BACKGROUND_BOTTOM, dtype=float)
    rows = (top + (bottom - top) * t).round().astype(np.uint8)
    return Image.fromarray(np.repeat(rows, size, axis=1), "RGB").convert("RGBA")


def render():
    icon = gradient(SIZE)
    icon.putalpha(squircle(SIZE))
    alpha = Image.open(HERE / "glyph.png").convert("RGBA").split()[3]
    alpha = alpha.crop(alpha.getbbox())
    scale = SIZE * GLYPH_FRACTION / max(alpha.size)
    alpha = alpha.resize((round(alpha.size[0] * scale), round(alpha.size[1] * scale)), Image.LANCZOS)
    glyph = Image.new("RGBA", alpha.size, GLYPH + (255,))
    glyph.putalpha(alpha)
    # The C opens to the right, so its optical centre sits a little right of the box centre.
    x = (SIZE - alpha.size[0]) // 2 + SIZE // 100
    y = (SIZE - alpha.size[1]) // 2
    icon.alpha_composite(glyph, (x, y))
    return icon


def macos(icon):
    """The icon on Apple's macOS grid: an 824-pixel body centred on 1024, over a soft shadow."""
    body = 824
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    scaled = icon.resize((body, body), Image.LANCZOS)
    offset = (SIZE - body) // 2
    shadow = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    shadow_alpha = Image.new("L", (SIZE, SIZE), 0)
    shadow_alpha.paste(scaled.split()[3].point(lambda a: a * 77 // 255), (offset, offset + 10))
    shadow.putalpha(shadow_alpha.filter(ImageFilter.GaussianBlur(14)))
    canvas.alpha_composite(shadow)
    canvas.alpha_composite(scaled, (offset, offset))
    return canvas


def main():
    icon = render()
    mac = macos(icon)
    mac_dir = HERE.parent / "macos"
    mac_dir.mkdir(exist_ok=True)
    mac.save(mac_dir / "diplaydesktop-1024.png")
    mac.save(mac_dir / "diplaydesktop.icns", sizes=[(s, s) for s in (16, 32, 64, 128, 256, 512, 1024)])
    icon.save(HERE.parent / "diplaydesktop.ico", sizes=[(s, s) for s in (16, 24, 32, 48, 64, 128, 256)])
    resources = HERE.parent.parent / "src" / "main" / "resources" / "icons"
    resources.mkdir(parents=True, exist_ok=True)
    for s in (16, 32, 48, 256):
        icon.resize((s, s), Image.LANCZOS).save(resources / f"diplaydesktop-{s}.png")


if __name__ == "__main__":
    main()
