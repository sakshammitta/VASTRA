#!/usr/bin/env python3
"""
Compare two architectures on the single-item Scan Closet flow:

  A) DINO + FashionCLIP  — detect, crop, classify the crop (current pipeline)
  B) FashionCLIP-only    — classify the WHOLE image, no Grounding-DINO

For each test image both paths are run and timed. The script reports whether
each path produces the expected category/subtype, and the time saved by skipping
DINO. This is a decision aid for whether the FashionCLIP-only path is a correct
MVP for single-item scans — it does NOT change any runtime behaviour.

Usage (inside the running container):
    docker cp shirt.jpeg vastra-cv:/tmp/shirt.jpeg
    docker exec vastra-cv python tests/benchmark_fastpath.py \
        /tmp/shirt.jpeg    TOP        t-shirt  \
        /tmp/trousers.jpeg BOTTOM     trousers \
        /tmp/shorts.jpeg   BOTTOM     shorts   \
        /tmp/hoodie.jpeg   TOP        hoodie   \
        /tmp/jacket.jpeg   OUTERWEAR  jacket   \
        /tmp/jeans.jpeg    BOTTOM     jeans

Arguments: triples of <image_path> <expected_category> <expected_subtype>
  expected_category matches ClothingCategory enum (TOP, BOTTOM, OUTERWEAR, …)
  expected_subtype  is a case-insensitive substring match.

Exit codes:
  0  FashionCLIP-only path matched expectations for every image
  1  FashionCLIP-only path failed on at least one image
  2  bad usage
"""
import sys
import time
from PIL import Image

from app.services import detector, embedder, segmenter
from app.models.schemas import Detection, BoundingBox


def _classify_crop(image, det) -> tuple[str, str, float]:
    crop = segmenter.segment_crop(image, det.bbox)
    cat, sub, conf = embedder.classify_subtype(crop, det.label)
    return cat.value, sub, conf


def _dino_path(image) -> tuple[str, str, float, float]:
    """Returns (category, subtype, conf, total_ms) or ('NONE',…) if no detection."""
    t0 = time.perf_counter()
    dets = detector.detect_clothing(image)
    if not dets:
        return "NONE", "—", 0.0, (time.perf_counter() - t0) * 1000
    cat, sub, conf = _classify_crop(image, dets[0])
    return cat, sub, conf, (time.perf_counter() - t0) * 1000


def _whole_path(image) -> tuple[str, str, float, float]:
    """FashionCLIP on the whole frame — no DINO."""
    t0 = time.perf_counter()
    full = Detection(
        label="whole-image",
        confidence=1.0,
        bbox=BoundingBox(x_min=0.0, y_min=0.0, x_max=1.0, y_max=1.0),
    )
    cat, sub, conf = _classify_crop(image, full)
    return cat, sub, conf, (time.perf_counter() - t0) * 1000


def benchmark_image(path: str, exp_cat: str, exp_sub: str) -> bool:
    print(f"\n{'='*78}")
    print(f"Image: {path}   expected: {exp_cat} / contains '{exp_sub}'")
    print(f"{'='*78}")
    image = Image.open(path).convert("RGB")

    d_cat, d_sub, d_conf, d_ms = _dino_path(image)
    w_cat, w_sub, w_conf, w_ms = _whole_path(image)

    def ok(cat, sub):
        return cat.upper() == exp_cat.upper() and exp_sub.lower() in sub.lower()

    d_ok, w_ok = ok(d_cat, d_sub), ok(w_cat, w_sub)
    agree = (d_cat == w_cat) and (d_sub == w_sub)

    print(f"  {'Path':<22}  {'Category':<12}  {'Subtype':<16}  {'Conf':>6}  {'Time':>9}  {'Pass'}")
    print(f"  {'-'*22}  {'-'*12}  {'-'*16}  {'-'*6}  {'-'*9}  {'-'*4}")
    print(f"  {'DINO+FashionCLIP':<22}  {d_cat:<12}  {d_sub:<16}  {d_conf:>6.3f}  {d_ms:>7.0f}ms  {'OK' if d_ok else 'FAIL'}")
    print(f"  {'FashionCLIP-only':<22}  {w_cat:<12}  {w_sub:<16}  {w_conf:>6.3f}  {w_ms:>7.0f}ms  {'OK' if w_ok else 'FAIL'}")
    print(f"  → agree={agree}   time saved by skipping DINO: {d_ms - w_ms:.0f}ms")

    return w_ok


def main() -> int:
    args = sys.argv[1:]
    if len(args) < 3 or len(args) % 3 != 0:
        print("Usage: python tests/benchmark_fastpath.py <image> <category> <subtype> [...]")
        return 2

    if not detector.is_loaded():
        print("Loading Grounding-DINO…")
        detector.load_models()
    if not embedder.is_loaded():
        print("Loading FashionCLIP…")
        embedder.load_models()
    if not detector.is_loaded() or not embedder.is_loaded():
        print("\n✗  Models failed to load.", file=sys.stderr)
        return 1

    triples = [(args[i], args[i+1], args[i+2]) for i in range(0, len(args), 3)]
    results = [benchmark_image(p, c, s) for p, c, s in triples]

    print(f"\n{'='*78}")
    print(f"FashionCLIP-only summary: {sum(results)}/{len(results)} images matched expectations")
    print("If all pass, the FashionCLIP-only path is a viable single-item MVP that")
    print("removes the ~18 s DINO step. Keep DINO for multi-item/outfit photos.")
    print(f"{'='*78}\n")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
