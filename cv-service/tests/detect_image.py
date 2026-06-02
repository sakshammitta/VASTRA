#!/usr/bin/env python3
"""
Run Grounding-DINO + FashionCLIP garment detection on a single local image.
Loads both models in-process so results are identical to what the API produces.
This NEVER touches R2 or the wardrobe database.

Usage (inside the running container):
    docker cp shirt.jpeg vastra-cv:/tmp/shirt.jpeg
    docker exec vastra-cv python tests/detect_image.py /tmp/shirt.jpeg

Or locally (once torch + transformers are installed):
    python tests/detect_image.py path/to/shirt.jpeg

Exit codes:
    0  — success (FashionCLIP classified every detection)
    1  — FashionCLIP failed to load; script refuses to fall back silently
    2  — bad usage
"""
import sys
from PIL import Image

from app.services import detector, embedder


def main(path: str) -> int:
    print(f"Loading image: {path}")
    image = Image.open(path).convert("RGB")

    # ── Load Grounding-DINO ────────────────────────────────────────────────────
    if not detector.is_loaded():
        print("Loading Grounding-DINO…")
        detector.load_models()

    if not detector.is_loaded():
        print(
            "\n⚠  Grounding-DINO is NOT loaded.\n"
            "   With CV_ALLOW_MOCK=false this returns ZERO detections (honest).\n"
            "   Build/run the Docker CV service so the model is available."
        )

    # ── Load FashionCLIP — REQUIRED; exit 1 if unavailable ────────────────────
    if not embedder.is_loaded():
        print("Loading FashionCLIP…")
        embedder.load_models()

    if not embedder.is_loaded():
        print(
            "\n✗  FashionCLIP FAILED TO LOAD.\n"
            "   Subtype classification would fall back to the unverified DINO label.\n"
            "   This script refuses to silently produce label-fallback results.\n"
            "   Check model weights in HF_HOME / model_cache and run:\n"
            "     docker exec vastra-cv curl -s http://localhost:8001/health\n"
            "   to confirm fashion_clip=true in the running service.",
            file=sys.stderr,
        )
        return 1

    print("FashionCLIP loaded — all subtypes will be verified by image-text similarity.\n")

    # ── Detect ─────────────────────────────────────────────────────────────────
    detections = detector.detect_clothing(image)

    from app.services import segmenter

    print(f"{'='*60}")
    print(f"Detections: {len(detections)}")
    print(f"{'='*60}")

    categories = []
    for i, d in enumerate(detections):
        crop = segmenter.segment_crop(image, d.bbox)
        cat, sub, conf = embedder.classify_subtype(crop, d.label)
        categories.append(cat.value)
        if conf <= 0.0:
            # Should never happen now that FashionCLIP is confirmed loaded above.
            verified = "⚠ label-fallback (FashionCLIP path unexpectedly failed)"
        else:
            verified = "FashionCLIP ✓"
        print(
            f"  [{i}] dino_label='{d.label}'  dino_conf={d.confidence:.2f}\n"
            f"       → subtype='{sub}'  category={cat.value}  "
            f"subtype_conf={conf:.2f} ({verified})"
        )

    tops = categories.count("TOP")
    bottoms = categories.count("BOTTOM")
    print(f"\n  TOP count={tops}   BOTTOM count={bottoms}")
    print(f"{'='*60}\n")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print("Usage: python tests/detect_image.py <image_path>")
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
