"""Export the HUD's empty-cover note from its original transparent ImageGen source.

Run with Pillow installed: python packaging/hud/make_note.py
Keeps the source untouched; normalizes the glyph's colour, scale and transparent margins.
"""
from pathlib import Path

from PIL import Image, ImageFilter

HERE = Path(__file__).resolve().parent
SIZE = 1024
GLYPH_FRACTION = 0.78
GREY = (154, 154, 160)


def main():
    with Image.open(HERE / "music-note-source.png") as source:
        # Remove isolated export specks while keeping the broad, rounded silhouette.
        alpha = source.convert("RGBA").getchannel("A").filter(ImageFilter.MedianFilter(3))
    bounds = alpha.point(lambda value: 255 if value >= 128 else 0).getbbox()
    if bounds is None:
        raise ValueError("music-note-source.png must contain a visible glyph")
    left, top, right, bottom = bounds
    alpha = alpha.crop((max(0, left - 2), max(0, top - 2), min(alpha.width, right + 2), min(alpha.height, bottom + 2)))
    scale = SIZE * GLYPH_FRACTION / max(alpha.size)
    alpha = alpha.resize((round(alpha.width * scale), round(alpha.height * scale)), Image.LANCZOS)
    glyph = Image.new("RGBA", alpha.size, GREY + (255,))
    glyph.putalpha(alpha)
    image = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    image.alpha_composite(glyph, ((SIZE - glyph.width) // 2, (SIZE - glyph.height) // 2))
    destination = HERE.parent.parent / "src/main/resources/icons/music-note.png"
    destination.parent.mkdir(parents=True, exist_ok=True)
    image.save(destination, optimize=True)


if __name__ == "__main__":
    main()
