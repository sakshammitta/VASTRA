#!/usr/bin/env python3
"""
Run Grounding-DINO garment detection on a single local image and print the
result. This NEVER touches R2 or the wardrobe database — it only loads the
model and prints what it sees, so you can verify recognition on a real photo
without saving anything.

Usage (inside the running container):
    docker cp shirt.jpeg vastra-cv:/tmp/shirt.jpeg
    docker exec vastra-cv python tests/detect_image.py /tmp/shirt.jpeg

Or locally (once torch + transformers are installed):
    python tests/detect_image.py path/to/shirt.jpeg
"""
import sys
from PIL import Image

from app.services import detector, embedder


def main(path: str) -> int:
    print(f"Loading image: {path}")
    image = Image.open(path).convert("RGB")

    if not detector.is_loaded():
        detector.load_models()

    if not detector.is_loaded():
        print(
            "\n⚠  Grounding-DINO is NOT loaded.\n"
            "   With CV_ALLOW_MOCK=false this returns ZERO detections (honest).\n"
            "   Build/run the Docker CV service so the model is available."
        )

    detections = detector.detect_clothing(image)

    print(f"\n{'='*52}")
    print(f"Detections: {len(detections)}")
    print(f"{'='*52}")

    categories = []
    for i, d in enumerate(detections):
        cat, sub = embedder._heuristic_category(d.label)
        categories.append(cat.value)
        print(
            f"  [{i}] label='{d.label}'  conf={d.confidence:.2f}  "
            f"category={cat.value}  bbox=({d.bbox.x_min:.2f},{d.bbox.y_min:.2f},"
            f"{d.bbox.x_max:.2f},{d.bbox.y_max:.2f})"
        )

    tops = categories.count("TOP")
    bottoms = categories.count("BOTTOM")
    print(f"\n  TOP count={tops}   BOTTOM count={bottoms}")
    print(f"{'='*52}\n")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python tests/detect_image.py <image_path>")
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
