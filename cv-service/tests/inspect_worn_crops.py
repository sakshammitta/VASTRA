#!/usr/bin/env python3
"""
Inspect worn-outfit detection crops in detail.

Runs the worn-outfit prompt at box=0.25, crops each detection from the image,
runs FashionCLIP on each crop, and prints a full breakdown of:
  - DINO label + confidence
  - FashionCLIP category / subtype / confidence
  - bounding-box area as fraction of image
  - which other detections overlap this one (IoU >= 0.30)
  - heuristic verdict: keep / likely-duplicate / likely-fragment

Also saves:
  - /tmp/worn_annotated.jpg  — full image with all boxes drawn and labelled
  - /tmp/worn_crop_N_<subtype>.jpg  — individual crop for each detection

Usage (inside the running container):
    docker cp outfit.jpg vastra-cv:/tmp/outfit.jpg
    docker exec vastra-cv python tests/inspect_worn_crops.py /tmp/outfit.jpg

    # Copy results back for visual inspection
    docker cp vastra-cv:/tmp/worn_annotated.jpg .
    docker cp vastra-cv:/tmp/worn_crop_0_t-shirt.jpg .
    # (or copy the whole /tmp directory)

No R2, no Redis, no Postgres. Read-only.
"""

import sys
import time
from pathlib import Path
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.services import detector, embedder, segmenter
from app.services.detector import (
    _DINO_MAX_SIDE, resize_for_inference,
    _grounding_dino_model as _dino_model,
    _grounding_dino_processor as _dino_proc,
)
from app.models.schemas import BoundingBox

WORN_PROMPT = (
    "shirt worn by person . t-shirt worn by person . "
    "jeans worn by person . trousers worn by person . pants worn by person . "
    "hoodie worn by person . jacket worn by person . "
    "shorts worn by person . skirt worn by person . shoes worn by person"
)
BOX_T = 0.25
TEXT_T = 0.20

OVERLAP_FLAG = 0.30    # IoU >= this → flag as overlapping
FRAGMENT_AREA = 0.04   # area < this fraction of image → flag as fragment

COLORS = ["red", "blue", "green", "orange", "purple", "cyan", "magenta"]


def _iou(a: dict, b: dict) -> float:
    ix1 = max(a["x_min"], b["x_min"]); iy1 = max(a["y_min"], b["y_min"])
    ix2 = min(a["x_max"], b["x_max"]); iy2 = min(a["y_max"], b["y_max"])
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    if inter == 0.0:
        return 0.0
    aa = (a["x_max"]-a["x_min"]) * (a["y_max"]-a["y_min"])
    ab = (b["x_max"]-b["x_min"]) * (b["y_max"]-b["y_min"])
    return inter / (aa + ab - inter)


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: python tests/inspect_worn_crops.py <image_path>", file=sys.stderr)
        return 2

    image_path = Path(sys.argv[1])
    if not image_path.exists():
        print(f"ERROR: {image_path} not found", file=sys.stderr)
        return 2

    print("Loading models…")
    if not detector.is_loaded():
        detector.load_models()
    if not detector.is_loaded():
        print("ERROR: Grounding-DINO failed to load", file=sys.stderr)
        return 1

    if not embedder.is_loaded():
        embedder.load_models()
    fashion_clip_available = embedder.is_loaded()
    if not fashion_clip_available:
        print("WARNING: FashionCLIP not loaded — subtype classification will be label-fallback only")

    import torch
    image = Image.open(image_path).convert("RGB")
    full_w, full_h = image.size
    print(f"Image: {image_path.name}  {full_w}x{full_h}")

    # ── DINO forward pass ─────────────────────────────────────────────────────
    inf_img = resize_for_inference(image, _DINO_MAX_SIDE)
    device = next(_dino_model.parameters()).device
    inputs = _dino_proc(images=inf_img, text=WORN_PROMPT, return_tensors="pt").to(device)
    t0 = time.perf_counter()
    with torch.no_grad():
        outputs = _dino_model(**inputs)
    fwd_ms = int((time.perf_counter() - t0) * 1000)
    print(f"DINO forward pass: {fwd_ms}ms")

    iw, ih = inf_img.size
    results = _dino_proc.post_process_grounded_object_detection(
        outputs, inputs.input_ids,
        box_threshold=BOX_T, text_threshold=TEXT_T,
        target_sizes=[inf_img.size[::-1]],
    )[0]

    raw = []
    for box, score, label in zip(results["boxes"], results["scores"], results["labels"]):
        x1, y1, x2, y2 = box.tolist()
        raw.append({
            "label": str(label).strip(),
            "conf":  round(float(score), 4),
            "x_min": round(max(0.0, x1 / iw), 4),
            "y_min": round(max(0.0, y1 / ih), 4),
            "x_max": round(min(1.0, x2 / iw), 4),
            "y_max": round(min(1.0, y2 / ih), 4),
        })
    raw.sort(key=lambda d: d["conf"], reverse=True)
    print(f"\n{len(raw)} raw detections from worn-outfit prompt at box={BOX_T} text={TEXT_T}")

    # ── Overlap map ───────────────────────────────────────────────────────────
    n = len(raw)
    overlaps = [[] for _ in range(n)]
    ious = {}
    for i in range(n):
        for j in range(i + 1, n):
            iou = _iou(raw[i], raw[j])
            ious[(i, j)] = iou
            if iou >= OVERLAP_FLAG:
                overlaps[i].append(j)
                overlaps[j].append(i)

    # ── Crop, classify, report ────────────────────────────────────────────────
    print(f"\n{'='*70}")
    print(f"  {'#':<3}  {'DINO label':<26}  {'conf':>5}  {'area':>5}  "
          f"{'FC subtype':<14}  {'FC conf':>6}  {'verdict':<18}  overlaps")
    print(f"  {'-'*3}  {'-'*26}  {'-'*5}  {'-'*5}  "
          f"{'-'*14}  {'-'*6}  {'-'*18}  {'-'*10}")

    verdicts = []
    for idx, det in enumerate(raw):
        bbox = BoundingBox(
            x_min=det["x_min"], y_min=det["y_min"],
            x_max=det["x_max"], y_max=det["y_max"],
        )
        area_frac = (det["x_max"]-det["x_min"]) * (det["y_max"]-det["y_min"])
        crop_img = segmenter.segment_crop(image, bbox)

        if fashion_clip_available:
            fc_cat, fc_sub, fc_conf = embedder.classify_subtype(crop_img, det["label"])
            fc_cat_str = fc_cat.value
        else:
            fc_cat_str, fc_sub, fc_conf = "?", det["label"], 0.0

        if area_frac < FRAGMENT_AREA:
            verdict = "likely-fragment"
        elif overlaps[idx]:
            dominated = any(raw[j]["conf"] > det["conf"] for j in overlaps[idx])
            verdict = "likely-duplicate" if dominated else "keep"
        else:
            verdict = "keep"

        verdicts.append(verdict)
        overlap_str = str(overlaps[idx]) if overlaps[idx] else "—"
        print(f"  #{idx:<2}  {det['label']:<26}  {det['conf']:>5.3f}  {area_frac:>5.3f}  "
              f"{fc_sub:<14}  {fc_conf:>6.3f}  {verdict:<18}  {overlap_str}")

        # Save crop
        safe_sub = fc_sub.replace(" ", "_").replace("/", "-")
        crop_path = Path(f"/tmp/worn_crop_{idx}_{safe_sub}.jpg")
        crop_img.convert("RGB").save(crop_path, quality=85)

    print(f"{'='*70}")
    keep_indices = [i for i, v in enumerate(verdicts) if v == "keep"]
    print(f"\nSuggested keep: {keep_indices}")
    for i in keep_indices:
        det = raw[i]
        print(f"  #{i}  {det['label']}  conf={det['conf']:.3f}")

    # ── IoU table (all pairs) ─────────────────────────────────────────────────
    if n > 1:
        print(f"\nIoU between all pairs:")
        for (i, j), iou in sorted(ious.items()):
            flag = " ← overlapping" if iou >= OVERLAP_FLAG else ""
            print(f"  #{i} ↔ #{j}  IoU={iou:.3f}{flag}")

    # ── Annotated full image ──────────────────────────────────────────────────
    ann = image.copy()
    draw = ImageDraw.Draw(ann)
    for idx, det in enumerate(raw):
        color = COLORS[idx % len(COLORS)]
        x1 = int(det["x_min"] * full_w); y1 = int(det["y_min"] * full_h)
        x2 = int(det["x_max"] * full_w); y2 = int(det["y_max"] * full_h)
        line_w = 4 if verdicts[idx] == "keep" else 2
        draw.rectangle([x1, y1, x2, y2], outline=color, width=line_w)
        label_text = f"#{idx} {raw[idx]['label'][:18]} {raw[idx]['conf']:.2f} [{verdicts[idx][:4]}]"
        box_w = len(label_text) * 7
        draw.rectangle([x1, max(0, y1-18), x1+box_w, y1], fill=color)
        draw.text((x1+2, max(0, y1-17)), label_text, fill="white")

    ann_path = Path("/tmp/worn_annotated.jpg")
    ann.save(ann_path, quality=88)
    print(f"\nSaved:")
    print(f"  Annotated image → {ann_path}")
    for idx in range(n):
        det = raw[idx]
        fc_sub = "unknown"
        if fashion_clip_available:
            # re-derive name from saved file pattern
            pass
        safe = raw[idx]["label"].replace(" ", "_").replace("/", "-")[:20]
        print(f"  Crop #{idx} → /tmp/worn_crop_{idx}_*.jpg")

    print(f"\nCopy back with:")
    print(f"  docker cp vastra-cv:/tmp/worn_annotated.jpg .")
    for idx in range(n):
        print(f"  docker cp vastra-cv:/tmp/worn_crop_{idx}_*.jpg .")

    return 0


if __name__ == "__main__":
    sys.exit(main())
