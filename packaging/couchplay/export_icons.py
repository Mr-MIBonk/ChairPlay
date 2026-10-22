"""Export CouchPlay's square icons and 16:9 Android TV / Google TV banners.

Run with Pillow and NumPy installed: python packaging/couchplay/export_icons.py
Uses ChairPlay's gradient and colour; the original ImageGen glyph.png remains untouched.
"""
from pathlib import Path
from runpy import run_path

from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
SQUARE_GLYPH_FRACTION = 0.88
TV_SIZE = (1280, 720)
FONT = HERE.parent.parent / "src/main/resources/fonts/Inter-SemiBold.ttf"


def sofa(width, colour):
    """Scale the original silhouette uniformly, preserving its aspect ratio."""
    with Image.open(HERE / "glyph.png") as source:
        alpha = source.convert("RGBA").getchannel("A")
    bounds = alpha.point(lambda value: 255 if value >= 128 else 0).getbbox()
    if bounds is None:
        raise ValueError("glyph.png must contain a visible sofa")
    left, top, right, bottom = bounds
    alpha = alpha.crop((max(0, left - 2), max(0, top - 2), min(alpha.width, right + 2), min(alpha.height, bottom + 2)))
    alpha = alpha.resize((width, round(alpha.height * width / alpha.width)), Image.LANCZOS)
    glyph = Image.new("RGBA", alpha.size, colour + (255,))
    glyph.putalpha(alpha)
    return glyph


def tv_banner(renderer, *, with_name):
    width, height = TV_SIZE
    # The shared gradient consists of uniform horizontal rows, so widening it changes no colours.
    canvas = renderer["gradient"](height).resize(TV_SIZE, Image.Resampling.NEAREST)
    glyph = sofa(round(width * (0.60 if with_name else renderer["GLYPH_FRACTION"])), renderer["GLYPH"])
    if with_name:
        name = "CouchPlay"
        font = ImageFont.truetype(str(FONT), 104)
        left, top, right, bottom = font.getbbox(name)
        text_height = bottom - top
        gap = 42
        glyph_top = (height - glyph.height - gap - text_height) // 2
        text_top = glyph_top + glyph.height + gap
        ImageDraw.Draw(canvas).text(
            ((width - (right - left)) / 2 - left, text_top - top),
            name, font=font, fill=renderer["GLYPH"] + (255,),
        )
    else:
        glyph_top = (height - glyph.height) // 2
    canvas.alpha_composite(glyph, ((width - glyph.width) // 2, glyph_top))
    # TV launcher banners fill the entire rectangle and have an opaque background.
    return canvas.convert("RGB")


def main():
    renderer = run_path(str(HERE.parent / "icon/make_icon.py"))
    render = renderer["render"]
    glyph = HERE / "glyph.png"
    render(glyph_path=glyph, glyph_fraction=SQUARE_GLYPH_FRACTION).save(HERE / "couchplay-1024.png", optimize=True)
    square = render(round_corners=False, glyph_path=glyph, glyph_fraction=SQUARE_GLYPH_FRACTION).convert("RGB")
    square.save(HERE / "couchplay-square-1024.png", optimize=True)
    square.resize((192, 192), Image.LANCZOS).save(HERE / "carplay-icon.png", optimize=True)
    tv = HERE / "androidtv"
    tv.mkdir(exist_ok=True)
    tv_banner(renderer, with_name=False).save(tv / "couchplay-icon-1280x720.png", optimize=True)
    banner = tv_banner(renderer, with_name=True)
    for size in (TV_SIZE, (640, 360), (320, 180)):
        banner.resize(size, Image.LANCZOS).save(tv / f"couchplay-banner-{size[0]}x{size[1]}.png", optimize=True)


if __name__ == "__main__":
    main()
