#!/usr/bin/env python3
"""
Benchmark Grounding-DINO inference time vs. accuracy at multiple resize levels.

For each test image the script runs DINO at four settings:
  - original  (no resize, DINO_MAX_SIDE=0)
  - 1600 px   longest-edge
  - 1280 px   (production default)
  - 1024 px

Then it runs FashionCLIP on the winning crop from the first detected item and
reports whether the category/subtype remain correct vs. the baseline.

Usage (inside the running container):
    docker cp shirt.jpeg vastra-cv:/tmp/shirt.jpeg
    docker exec vastra-cv python tests/benchmark_resize.py \
        /tmp/shirt.jpeg TOP t-shirt \
        /tmp/trousers.jpeg BOTTOM trousers

Arguments: pairs of  <image_path> <expected_category> <expected_subtype>
  expected_category must match ClothingCategory enum values (TOP, BOTTOM, …)
  expected_subtype  is a case-insensitive substring match (t-shirt, trousers…)

Exit codes:
  0  all resize levels preserved accuracy for all images
  1  at least one resize level changed category or subtype from baseline
  2  bad usage
"""
import sys
import time
from PIL import Image

from app.services import detector, embedder, segmenter
from app.services.detector import resize_for_inference

MAX_SIDES = [0, 1600, 1280, 1024]   # 0 = no resize (original)
LABEL_WIDTH = 10


def _label(max_side: int) -> str:
    return "original" if max_side == 0 else f"{max_side}px"


def _run_one(image: Image.Image, max_side: int) -> tuple[list, float]:
    """Return (detections, inference_ms) for one resize level."""
    inf_img = resize_for_inference(image, max_side)
    t0 = time.perf_counter()
    dets = detector.detect_clothing(inf_img)
    ms = (time.perf_counter() - t0) * 1000
    return dets, ms


def _classify_first(image: Image.Image, det) -> tuple[str, str, float]:
    """FashionCLIP subtype on the crop of the first detection from *image*."""
    crop = segmenter.segment_crop(image, det.bbox)
    cat, sub, conf = embedder.classify_subtype(crop, det.label)
    return cat.value, sub, conf


def benchmark_image(
    path: str,
    expected_cat: str,
    expected_sub: str,
) -> bool:
    """Run all resize levels for one image. Returns True if all pass."""
    print(f"\n{'='*70}")
    print(f"Image: {path}")
    print(f"Expected: category={expected_cat}  subtype contains '{expected_sub}'")
    print(f"{'='*70}")
    print(f"  {'Level':<{LABEL_WIDTH}}  {'Input size':<14}  {'DINO ms':>8}  "
          f"{'Dets':>4}  {'Category':<12}  {'Subtype':<16}  {'Conf':>6}  {'Pass?'}")
    print(f"  {'-'*{LABEL_WIDTH}}  {'-'*14}  {'-'*8}  {'-'*4}  {'-'*12}  {'-'*16}  {'-'*6}  {'-'*5}")

    image = Image.open(path).convert("RGB")
    orig_w, orig_h = image.size
    baseline_cat = baseline_sub = None
    all_pass = True

    for max_side in MAX_SIDES:
        inf_img = resize_for_inference(image, max_side)
        inf_size = f"{inf_img.width}x{inf_img.height}"

        t0 = time.perf_counter()
        dets = detector.detect_clothing(inf_img)
        dino_ms = (time.perf_counter() - t0) * 1000

        if not dets:
            print(f"  {_label(max_side):<{LABEL_WIDTH}}  {inf_size:<14}  {dino_ms:>8.0f}  "
                  f"{'0':>4}  {'NO DETECTION':<12}  {'—':<16}  {'—':>6}  FAIL")
            all_pass = False
            continue

        cat, sub, conf = _classify_first(image, dets[0])

        # Baseline is the original-resolution result.
        if baseline_cat is None:
            baseline_cat, baseline_sub = cat, sub

        cat_ok   = cat.upper() == expected_cat.upper()
        sub_ok   = expected_sub.lower() in sub.lower()
        cat_same = cat == baseline_cat
        sub_same = sub == baseline_sub
        passed   = cat_ok and sub_ok

        if not passed:
            all_pass = False

        flags = ""
        if not cat_same: flags += " cat≠baseline"
        if not sub_same: flags += " sub≠baseline"

        print(f"  {_label(max_side):<{LABEL_WIDTH}}  {inf_size:<14}  {dino_ms:>8.0f}  "
              f"{len(dets):>4}  {cat:<12}  {sub:<16}  {conf:>6.3f}  "
              f"{'PASS' if passed else 'FAIL'}{flags}")

    return all_pass


def main() -> int:
    args = sys.argv[1:]
    if len(args) < 3 or len(args) % 3 != 0:
        print(
            "Usage: python tests/benchmark_resize.py "
            "<image> <category> <subtype> [<image> <category> <subtype> ...]"
        )
        return 2

    if not detector.is_loaded():
        print("Loading Grounding-DINO…")
        detector.load_models()
    if not embedder.is_loaded():
        print("Loading FashionCLIP…")
        embedder.load_models()

    if not detector.is_loaded():
        print("\n✗  Grounding-DINO failed to load.", file=sys.stderr)
        return 1
    if not embedder.is_loaded():
        print("\n✗  FashionCLIP failed to load.", file=sys.stderr)
        return 1

    triples = [(args[i], args[i+1], args[i+2]) for i in range(0, len(args), 3)]
    results = []
    for path, cat, sub in triples:
        ok = benchmark_image(path, cat, sub)
        results.append(ok)

    print(f"\n{'='*70}")
    passed = sum(results)
    total  = len(results)
    print(f"Summary: {passed}/{total} images passed at all resize levels")
    print(f"{'='*70}\n")
    return 0 if all(results) else 1


if __name__ == "__main__":
    sys.exit(main())
