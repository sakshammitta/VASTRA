#!/usr/bin/env python3
"""
Outfit-detection diagnostic tool.

Runs Grounding-DINO on a real outfit photo at multiple box/text threshold
levels and with multiple prompt variants, printing ALL raw boxes before any
NMS or deduplication. Tells you whether DINO sees garments at all (just at
lower confidence than the production threshold) or genuinely produces zero
detections.

Usage (inside the running container):
    docker cp outfit.jpg vastra-cv:/tmp/outfit.jpg
    docker exec vastra-cv python tests/diagnose_outfit_detection.py /tmp/outfit.jpg

Optional flags:
    --no-prompts     skip the alternative-prompt sweep (faster)
    --save-crops     save per-detection crop JPEGs to /tmp/crops/ for visual inspection

Output:
    - Raw DINO boxes at each threshold level (label, conf, bbox) before NMS
    - Summary table: which threshold + prompt variant first produces detections
    - Production-settings result for direct comparison
"""

import sys
import time
import json
import argparse
from pathlib import Path
from PIL import Image, ImageDraw

# ── ensure app package is importable ─────────────────────────────────────────
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.services import detector, embedder
from app.services.detector import (
    CLOTHING_PROMPT, _grounding_dino_model, _grounding_dino_processor,
    resize_for_inference, _DINO_MAX_SIDE,
)


# ── Prompt variants to try ────────────────────────────────────────────────────
# Grounding-DINO is a phrase-grounding model: it looks for things that *match*
# the text. For worn garments on a person, short concrete noun phrases tend to
# work better than abstract terms like "top" or "apparel".
PROMPT_VARIANTS: dict[str, str] = {
    "production": CLOTHING_PROMPT,

    # Worn-garment focus: shorter, concrete nouns, add body-region anchors
    "worn-nouns": (
        "shirt . t-shirt . polo shirt . sweater . hoodie . blouse . "
        "pants . trousers . jeans . shorts . skirt . "
        "jacket . coat . blazer . "
        "dress . jumpsuit . "
        "shoes . sneakers . boots . sandals"
    ),

    # Explicit worn-clothing phrasing
    "worn-phrases": (
        "shirt worn by person . t-shirt worn by person . "
        "jeans worn by person . trousers worn by person . pants worn by person . "
        "hoodie worn by person . jacket worn by person . "
        "shoes worn by person . sneakers worn by person"
    ),

    # Very short: just the most common top/bottom nouns
    "minimal": (
        "shirt . t-shirt . jeans . trousers . pants . shorts . "
        "hoodie . jacket . shoes . sneakers"
    ),

    # Body-region terms used in fashion-detection literature
    "body-region": (
        "upper body clothing . lower body clothing . "
        "top garment . bottom garment . "
        "shirt . t-shirt . jeans . trousers . jacket . shoes"
    ),
}

# Threshold sweep: pairs of (box_threshold, text_threshold)
THRESHOLD_LEVELS = [
    (0.35, 0.25),   # production default
    (0.25, 0.20),   # moderately relaxed
    (0.15, 0.10),   # very relaxed — anything the model sees
    (0.10, 0.05),   # near-floor — reveals hidden detections
]


def _raw_dino(image: Image.Image, prompt: str, box_thresh: float, text_thresh: float) -> list[dict]:
    """
    Run Grounding-DINO and return ALL raw boxes (before NMS/dedup) as a list
    of {label, conf, x_min, y_min, x_max, y_max} dicts (normalized 0-1).
    Returns [] if the model is not loaded.
    """
    if not detector.is_loaded():
        return []

    import torch
    inf_img = resize_for_inference(image, _DINO_MAX_SIDE)
    device = next(_grounding_dino_model.parameters()).device
    inputs = _grounding_dino_processor(
        images=inf_img, text=prompt, return_tensors="pt"
    ).to(device)

    with torch.no_grad():
        outputs = _grounding_dino_model(**inputs)

    results = _grounding_dino_processor.post_process_grounded_object_detection(
        outputs,
        inputs.input_ids,
        box_threshold=box_thresh,
        text_threshold=text_thresh,
        target_sizes=[inf_img.size[::-1]],
    )[0]

    w, h = inf_img.size
    raw = []
    for box, score, label in zip(results["boxes"], results["scores"], results["labels"]):
        x1, y1, x2, y2 = box.tolist()
        raw.append({
            "label":  str(label).strip(),
            "conf":   round(float(score), 4),
            "x_min":  round(max(0.0, x1 / w), 4),
            "y_min":  round(max(0.0, y1 / h), 4),
            "x_max":  round(min(1.0, x2 / w), 4),
            "y_max":  round(min(1.0, y2 / h), 4),
        })
    raw.sort(key=lambda d: d["conf"], reverse=True)
    return raw


def _save_crops(image: Image.Image, detections: list[dict], out_dir: Path, prefix: str) -> None:
    """Save one JPEG per detection to out_dir for visual inspection."""
    out_dir.mkdir(parents=True, exist_ok=True)
    w, h = image.size
    for i, d in enumerate(detections):
        x1 = int(d["x_min"] * w)
        y1 = int(d["y_min"] * h)
        x2 = int(d["x_max"] * w)
        y2 = int(d["y_max"] * h)
        crop = image.crop((x1, y1, x2, y2))
        fname = out_dir / f"{prefix}_{i:02d}_{d['label'].replace(' ', '_')}_{d['conf']:.2f}.jpg"
        crop.save(fname)


def _draw_boxes(image: Image.Image, detections: list[dict]) -> Image.Image:
    """Return a copy of the image with detection boxes drawn on it."""
    vis = image.copy()
    draw = ImageDraw.Draw(vis)
    w, h = vis.size
    for d in detections:
        x1, y1 = int(d["x_min"] * w), int(d["y_min"] * h)
        x2, y2 = int(d["x_max"] * w), int(d["y_max"] * h)
        draw.rectangle([x1, y1, x2, y2], outline="red", width=3)
        draw.text((x1 + 4, y1 + 4), f"{d['label']} {d['conf']:.2f}", fill="red")
    return vis


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("image", help="Path to the outfit photo")
    parser.add_argument("--no-prompts", action="store_true",
                        help="Only run production prompt, skip alternative-prompt sweep")
    parser.add_argument("--save-crops", action="store_true",
                        help="Save per-detection crops to /tmp/crops/")
    args = parser.parse_args()

    image_path = Path(args.image)
    if not image_path.exists():
        print(f"ERROR: image not found: {image_path}", file=sys.stderr)
        return 2

    print("\nLoading Grounding-DINO…")
    if not detector.is_loaded():
        detector.load_models()
    if not detector.is_loaded():
        print("ERROR: Grounding-DINO failed to load.", file=sys.stderr)
        return 1

    image = Image.open(image_path).convert("RGB")
    print(f"Image: {image_path.name}  {image.width}x{image.height}px")

    crop_dir = Path("/tmp/crops") if args.save_crops else None

    # ── Section 1: Threshold sweep on the production prompt ──────────────────
    print(f"\n{'='*70}")
    print("SECTION 1: Threshold sweep — production prompt")
    print(f"{'='*70}")
    print(f"  Prompt: {CLOTHING_PROMPT[:80]}…")
    print()

    prod_results: dict[str, list[dict]] = {}
    for box_t, text_t in THRESHOLD_LEVELS:
        t0 = time.perf_counter()
        raw = _raw_dino(image, CLOTHING_PROMPT, box_t, text_t)
        ms = (time.perf_counter() - t0) * 1000
        key = f"box={box_t} text={text_t}"
        prod_results[key] = raw
        flag = " ← PRODUCTION" if box_t == 0.35 else ""
        print(f"  [{key}]{flag}  {len(raw)} raw boxes  ({ms:.0f}ms)")
        for d in raw[:10]:  # print up to 10
            print(f"      {d['label']:<22}  conf={d['conf']:.3f}  "
                  f"bbox=({d['x_min']:.2f},{d['y_min']:.2f}→{d['x_max']:.2f},{d['y_max']:.2f})")
        if len(raw) > 10:
            print(f"      … {len(raw) - 10} more boxes not shown")
        if args.save_crops and raw:
            _save_crops(image, raw, crop_dir, f"thresh_{box_t}_{text_t}")
        print()

    # ── Section 2: Alternative prompt sweep at box=0.25 text=0.20 ────────────
    if not args.no_prompts:
        print(f"\n{'='*70}")
        print("SECTION 2: Alternative prompt sweep  (box=0.25 text=0.20)")
        print(f"{'='*70}")
        print("  (Only the best 8 detections per prompt shown)\n")

        for variant_name, prompt in PROMPT_VARIANTS.items():
            if variant_name == "production":
                continue  # already covered above
            t0 = time.perf_counter()
            raw = _raw_dino(image, prompt, 0.25, 0.20)
            ms = (time.perf_counter() - t0) * 1000
            print(f"  [{variant_name}]  {len(raw)} boxes  ({ms:.0f}ms)")
            print(f"  Prompt: {prompt[:90]}")
            for d in raw[:8]:
                print(f"      {d['label']:<22}  conf={d['conf']:.3f}  "
                      f"bbox=({d['x_min']:.2f},{d['y_min']:.2f}→{d['x_max']:.2f},{d['y_max']:.2f})")
            if args.save_crops and raw:
                _save_crops(image, raw, crop_dir, f"variant_{variant_name}")
            print()

    # ── Section 3: Production pipeline result (with NMS/dedup) ───────────────
    print(f"\n{'='*70}")
    print("SECTION 3: Production pipeline result  (box=0.35 text=0.25 + NMS)")
    print(f"{'='*70}")
    t0 = time.perf_counter()
    production_dets = detector.detect_clothing(image)
    ms = (time.perf_counter() - t0) * 1000
    print(f"  detect_clothing() returned {len(production_dets)} items  ({ms:.0f}ms)")
    for d in production_dets:
        print(f"      {d.label:<22}  conf={d.confidence:.3f}  "
              f"bbox=({d.bbox.x_min:.2f},{d.bbox.y_min:.2f}→{d.bbox.x_max:.2f},{d.bbox.y_max:.2f})")

    if args.save_crops and production_dets:
        from app.services import segmenter
        for i, det in enumerate(production_dets):
            crop = segmenter.segment_crop(image, det.bbox)
            out = Path("/tmp/crops") / f"production_{i:02d}_{det.label.replace(' ', '_')}.jpg"
            out.parent.mkdir(parents=True, exist_ok=True)
            crop.save(out)

    # ── Section 4: Summary / diagnosis ───────────────────────────────────────
    print(f"\n{'='*70}")
    print("SECTION 4: Diagnosis summary")
    print(f"{'='*70}")

    prod_at_thresh = {k: v for k, v in prod_results.items() if v}
    if not any(prod_results.values()):
        print("  RESULT: Grounding-DINO produced ZERO detections at ALL threshold levels")
        print("  with the production prompt. This means the model is not finding clothing")
        print("  in this image at all — not a threshold issue.")
        print("  → Try alternative prompts or inspect the image for occlusion/lighting.")
    else:
        first_hit = next(k for k, v in prod_results.items() if v)
        prod_raw = prod_results[first_hit]
        top_confs = [d["conf"] for d in prod_raw[:5]]
        print(f"  RESULT: Detections first appear at [{first_hit}]")
        print(f"  Top-5 confidences at that level: {[f'{c:.3f}' for c in top_confs]}")
        if first_hit.startswith("box=0.35"):
            print("  ✓ Production threshold is sufficient — verify NMS isn't suppressing them.")
        elif first_hit.startswith("box=0.25") or first_hit.startswith("box=0.15"):
            print("  → Detections exist but below the production threshold (0.35).")
            print("  Recommendation: lower box_threshold to 0.25 for outfit/worn photos.")
        else:
            print("  → Detections only visible at near-floor thresholds (conf ≤ 0.15).")
            print("  This suggests the model struggles with this image; prompt change needed.")

    if args.save_crops:
        print(f"\n  Crops saved to /tmp/crops/")
        print(f"  Copy back: docker cp vastra-cv:/tmp/crops ./crops/")

    print()
    return 0 if production_dets else 1


if __name__ == "__main__":
    sys.exit(main())
