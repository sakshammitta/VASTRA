"""
Generate minimal synthetic fixture images used by the acceptance tests.

Run once:  python tests/fixtures/generate.py
The output JPEGs are committed to the repo so tests run without PIL draw deps.
"""
from pathlib import Path
from PIL import Image, ImageDraw

OUT = Path(__file__).parent


def _save(img: Image.Image, name: str) -> None:
    path = OUT / name
    img.save(path, "JPEG", quality=90)
    print(f"  wrote {path}  ({img.size})")


def make_shirt(size=(512, 640)) -> Image.Image:
    """Plain white shirt silhouette on a neutral background."""
    img = Image.new("RGB", size, (230, 225, 218))
    draw = ImageDraw.Draw(img)
    w, h = size
    # Body
    draw.rectangle([w * 0.25, h * 0.15, w * 0.75, h * 0.72], fill=(245, 240, 232))
    # Collar
    draw.polygon(
        [(w * 0.45, h * 0.15), (w * 0.55, h * 0.15), (w * 0.50, h * 0.28)],
        fill=(200, 195, 188),
    )
    # Sleeves
    draw.rectangle([w * 0.05, h * 0.15, w * 0.28, h * 0.45], fill=(245, 240, 232))
    draw.rectangle([w * 0.72, h * 0.15, w * 0.95, h * 0.45], fill=(245, 240, 232))
    return img


def make_trousers(size=(512, 640)) -> Image.Image:
    """Plain dark trousers silhouette on a neutral background."""
    img = Image.new("RGB", size, (230, 225, 218))
    draw = ImageDraw.Draw(img)
    w, h = size
    # Waistband
    draw.rectangle([w * 0.20, h * 0.10, w * 0.80, h * 0.22], fill=(60, 55, 50))
    # Left leg
    draw.rectangle([w * 0.20, h * 0.22, w * 0.50, h * 0.90], fill=(70, 65, 58))
    # Right leg
    draw.rectangle([w * 0.50, h * 0.22, w * 0.80, h * 0.90], fill=(65, 60, 54))
    return img


def make_outfit(size=(512, 768)) -> Image.Image:
    """Shirt (top half) + trousers (bottom half) together."""
    img = Image.new("RGB", size, (230, 225, 218))
    draw = ImageDraw.Draw(img)
    w, h = size
    mid = h // 2
    # Shirt section
    draw.rectangle([w * 0.20, h * 0.05, w * 0.80, mid - 10], fill=(245, 240, 232))
    draw.polygon(
        [(w * 0.44, h * 0.05), (w * 0.56, h * 0.05), (w * 0.50, h * 0.16)],
        fill=(200, 195, 188),
    )
    draw.rectangle([w * 0.04, h * 0.05, w * 0.22, h * 0.32], fill=(245, 240, 232))
    draw.rectangle([w * 0.78, h * 0.05, w * 0.96, h * 0.32], fill=(245, 240, 232))
    # Trousers section
    draw.rectangle([w * 0.18, mid, w * 0.82, mid + 20], fill=(60, 55, 50))
    draw.rectangle([w * 0.18, mid + 20, w * 0.49, h * 0.95], fill=(70, 65, 58))
    draw.rectangle([w * 0.51, mid + 20, w * 0.82, h * 0.95], fill=(65, 60, 54))
    return img


if __name__ == "__main__":
    print("Generating fixture images…")
    _save(make_shirt(), "single_shirt.jpg")
    _save(make_trousers(), "single_trousers.jpg")
    _save(make_outfit(), "outfit_shirt_trousers.jpg")
    print("Done.")
