import uuid
import logging
import time
from typing import Optional
from fastapi import APIRouter, HTTPException, UploadFile, File
from pydantic import BaseModel
from PIL import Image
import io

from app.models.schemas import (
    ScanRequest, ScanResponse, Detection, BoundingBox,
    DetectedItem, EmbedResponse,
)
from app.services import detector, segmenter, embedder, r2_client as r2_module

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/scan", tags=["scan"])

_r2 = r2_module.R2Client()


@router.post("", response_model=ScanResponse)
async def scan_image(request: ScanRequest):
    """
    Run Grounding-DINO clothing detection on an image already uploaded to R2.
    Returns bounding boxes with labels and confidence scores.
    """
    t0 = time.perf_counter()
    try:
        image = _r2.download_image(request.image_key)
    except Exception as e:
        logger.error(f"Failed to download image {request.image_key}: {e}")
        raise HTTPException(status_code=404, detail=f"Image not found: {request.image_key}")
    t_fetch = time.perf_counter()
    logger.info(f"timing r2-fetch: {(t_fetch - t0)*1000:.0f}ms  {image.width}x{image.height}")

    detections = detector.detect_clothing(image)
    logger.info(f"timing dino-inference: {(time.perf_counter() - t_fetch)*1000:.0f}ms  detections={len(detections)}")

    return ScanResponse(
        job_id=str(uuid.uuid4()),
        detections=detections,
        image_width=image.width,
        image_height=image.height,
    )


@router.post("/inspect", response_model=EmbedResponse)
async def inspect_uploaded_image(file: UploadFile = File(...)):
    """
    READ-ONLY verification endpoint. Runs the FULL CV pipeline on a directly
    uploaded image and returns the classified items — entirely in-memory.

    Pipeline: Grounding-DINO detection → crop → FashionCLIP subtype
    classification + 512-dim embedding → dominant colors.

    Nothing is persisted: no R2 upload, no Redis job, no Postgres/wardrobe
    write. Use this to confirm real detection + FashionCLIP subtype classification
    on a real photo. Each item's `subtype_confidence` is > 0 only when
    FashionCLIP actually produced the subtype (0.0 means unverified label
    fallback). `embedding` is null when FashionCLIP is not loaded.
    """
    try:
        contents = await file.read()
        image = Image.open(io.BytesIO(contents)).convert("RGB")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid image: {e}")

    detections = detector.detect_clothing(image)

    items = []
    for detection in detections:
        crop = segmenter.segment_crop(image, detection.bbox)
        colors = embedder.extract_colors(crop, k=3)
        category, sub_category, subtype_conf = embedder.classify_subtype(
            crop, detection.label
        )
        embedding = embedder.get_embedding(crop)
        items.append(DetectedItem(
            detection=detection,
            embedding=embedding,
            color_palette=colors,
            category=category,
            sub_category=sub_category,
            subtype_confidence=subtype_conf,
            crop_key=None,
        ))

    return EmbedResponse(items=items)


class RawDetection(BaseModel):
    label: str
    conf: float
    x_min: float
    y_min: float
    x_max: float
    y_max: float


class DiagnoseCase(BaseModel):
    name: str                          # stable identifier, e.g. "production@0.35"
    prompt: str                        # the exact prompt text used
    box_threshold: float
    text_threshold: float
    detections: list[RawDetection]     # raw boxes (pre-NMS), highest conf first
    count: int                         # len(detections), for quick scanning
    forward_ms: int                    # DINO forward-pass time for this case
    error: Optional[str] = None        # populated instead of detections if the case failed


class DiagnoseResponse(BaseModel):
    image_width: int
    image_height: int
    cases: list[DiagnoseCase]          # one entry per (prompt, threshold) tested
    production_detections: list[Detection]   # post-NMS result at production settings
    diagnosis: str                     # human-readable one-line summary


# A worn-outfit-specific prompt: concrete nouns plus body-region anchoring, the
# phrasing most likely to help Grounding-DINO localise garments on a person.
_WORN_OUTFIT_PROMPT = (
    "shirt worn by person . t-shirt worn by person . "
    "jeans worn by person . trousers worn by person . pants worn by person . "
    "hoodie worn by person . jacket worn by person . "
    "shorts worn by person . skirt worn by person . shoes worn by person"
)


@router.post("/diagnose", response_model=DiagnoseResponse)
async def diagnose_outfit_detection(file: UploadFile = File(...)):
    """
    READ-ONLY diagnostic endpoint for the worn-outfit "0 items detected" case.

    Runs THREE meaningful cases only (two DINO forward passes total, kept light
    because each forward pass is ~15s on CPU):

      1. production@0.35  — current prompt, current threshold (the failing case)
      2. production@0.25  — current prompt, lower threshold (reuses pass #1)
      3. worn-outfit@0.25 — worn-garment prompt, lower threshold (second pass)

    For each case it returns the RAW boxes (label, confidence, normalized bbox)
    BEFORE any NMS/deduplication, so you can tell whether the problem is the
    threshold, the prompt wording, or genuine detector blindness.

    Each case is isolated: if one fails, its `error` field is populated and the
    others still return. The endpoint always returns valid JSON.

    Nothing is persisted. No R2, no Redis, no Postgres writes.

    Example:
        curl -X POST http://localhost:8000/scan/diagnose \\
             -F "file=@outfit.jpg" | python3 -m json.tool
    """
    try:
        contents = await file.read()
        image = Image.open(io.BytesIO(contents)).convert("RGB")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid image: {e}")

    if not detector.is_loaded():
        raise HTTPException(status_code=503, detail="Grounding-DINO model not loaded")

    import torch
    from app.services.detector import (
        _grounding_dino_model as _dino_model,
        _grounding_dino_processor as _dino_proc,
        resize_for_inference, _DINO_MAX_SIDE,
    )

    inf_img = resize_for_inference(image, _DINO_MAX_SIDE)
    device = next(_dino_model.parameters()).device
    w, h = inf_img.size

    def _forward(prompt: str):
        """Run a single DINO forward pass for *prompt*; returns (outputs, input_ids, ms)."""
        inputs = _dino_proc(images=inf_img, text=prompt, return_tensors="pt").to(device)
        t0 = time.perf_counter()
        with torch.no_grad():
            outputs = _dino_model(**inputs)
        ms = int((time.perf_counter() - t0) * 1000)
        logger.info(f"timing diagnose-forward: {ms}ms  prompt='{prompt[:40]}…'")
        return outputs, inputs.input_ids, ms

    def _decode(outputs, input_ids, box_t: float, text_t: float) -> list[RawDetection]:
        """Post-process one forward pass at the given thresholds into raw detections."""
        results = _dino_proc.post_process_grounded_object_detection(
            outputs, input_ids,
            box_threshold=box_t, text_threshold=text_t,
            target_sizes=[inf_img.size[::-1]],
        )[0]
        raw = []
        for box, score, label in zip(results["boxes"], results["scores"], results["labels"]):
            x1, y1, x2, y2 = box.tolist()
            raw.append(RawDetection(
                label=str(label).strip(),
                conf=round(float(score), 4),
                x_min=round(max(0.0, x1 / w), 4),
                y_min=round(max(0.0, y1 / h), 4),
                x_max=round(min(1.0, x2 / w), 4),
                y_max=round(min(1.0, y2 / h), 4),
            ))
        raw.sort(key=lambda d: d.conf, reverse=True)
        return raw

    cases: list[DiagnoseCase] = []

    # ── Forward pass #1: production prompt (reused at two thresholds) ──────────
    prod_outputs = prod_ids = None
    prod_ms = 0
    try:
        prod_outputs, prod_ids, prod_ms = _forward(detector.CLOTHING_PROMPT)
    except Exception as e:
        logger.error(f"diagnose: production forward pass failed: {e}")

    for name, box_t, text_t in [
        ("production@0.35", 0.35, 0.25),
        ("production@0.25", 0.25, 0.20),
    ]:
        if prod_outputs is None:
            cases.append(DiagnoseCase(
                name=name, prompt=detector.CLOTHING_PROMPT,
                box_threshold=box_t, text_threshold=text_t,
                detections=[], count=0, forward_ms=prod_ms,
                error="production forward pass failed",
            ))
            continue
        try:
            dets = _decode(prod_outputs, prod_ids, box_t, text_t)
            cases.append(DiagnoseCase(
                name=name, prompt=detector.CLOTHING_PROMPT,
                box_threshold=box_t, text_threshold=text_t,
                detections=dets, count=len(dets), forward_ms=prod_ms,
            ))
        except Exception as e:
            logger.error(f"diagnose: decode {name} failed: {e}")
            cases.append(DiagnoseCase(
                name=name, prompt=detector.CLOTHING_PROMPT,
                box_threshold=box_t, text_threshold=text_t,
                detections=[], count=0, forward_ms=prod_ms, error=str(e),
            ))

    # ── Forward pass #2: worn-outfit prompt at the lower threshold ────────────
    try:
        worn_outputs, worn_ids, worn_ms = _forward(_WORN_OUTFIT_PROMPT)
        dets = _decode(worn_outputs, worn_ids, 0.25, 0.20)
        cases.append(DiagnoseCase(
            name="worn-outfit@0.25", prompt=_WORN_OUTFIT_PROMPT,
            box_threshold=0.25, text_threshold=0.20,
            detections=dets, count=len(dets), forward_ms=worn_ms,
        ))
    except Exception as e:
        logger.error(f"diagnose: worn-outfit case failed: {e}")
        cases.append(DiagnoseCase(
            name="worn-outfit@0.25", prompt=_WORN_OUTFIT_PROMPT,
            box_threshold=0.25, text_threshold=0.20,
            detections=[], count=0, forward_ms=0, error=str(e),
        ))

    # ── Production pipeline result (post-NMS), best-effort ────────────────────
    try:
        production_dets = detector.detect_clothing(image)
    except Exception as e:
        logger.error(f"diagnose: production pipeline failed: {e}")
        production_dets = []

    # ── One-line diagnosis from the case results (no hardcoded keys) ──────────
    by_name = {c.name: c for c in cases}
    prod35 = by_name.get("production@0.35")
    prod25 = by_name.get("production@0.25")
    worn25 = by_name.get("worn-outfit@0.25")

    if prod35 and prod35.count > 0:
        diagnosis = (
            f"Production threshold sufficient: {prod35.count} raw boxes at 0.35, "
            f"{len(production_dets)} after NMS. Investigate NMS if app still shows 0."
        )
    elif prod25 and prod25.count > 0:
        top = prod25.detections[0].conf
        diagnosis = (
            f"Detections exist at box=0.25 (top conf={top:.3f}) but not at 0.35. "
            "Likely fix: lower box_threshold to 0.25 for outfit photos."
        )
    elif worn25 and worn25.count > 0:
        top = worn25.detections[0].conf
        diagnosis = (
            f"Only the worn-outfit prompt detects anything (top conf={top:.3f} at 0.25). "
            "Likely fix: prompt wording, not just threshold."
        )
    else:
        diagnosis = (
            "ZERO detections across all three cases. Neither lower threshold nor the "
            "worn-outfit prompt helped — points to detector capability/image issue."
        )

    return DiagnoseResponse(
        image_width=image.width,
        image_height=image.height,
        cases=cases,
        production_detections=production_dets,
        diagnosis=diagnosis,
    )


class CropInspection(BaseModel):
    index: int
    dino_label: str
    dino_conf: float
    bbox: dict                        # {x_min, y_min, x_max, y_max} normalized
    area_fraction: float              # fraction of image area this box covers
    fashionclip_category: str
    fashionclip_subtype: str
    fashionclip_conf: float
    # Indices of other detections whose bbox significantly overlaps this one (IoU >= 0.3)
    overlaps_with: list[int]
    # Verdict: "keep" | "likely-duplicate" | "likely-fragment"
    verdict: str
    # base64-encoded JPEG of the crop (small, for visual inspection)
    crop_jpeg_b64: str


class WornInspectResponse(BaseModel):
    image_width: int
    image_height: int
    prompt_used: str
    box_threshold: float
    text_threshold: float
    raw_count: int                    # total raw detections before any filtering
    crops: list[CropInspection]
    # base64-encoded JPEG of the full image with bboxes drawn on it
    annotated_jpeg_b64: str
    # Which crop indices survive the overlap/area filters (not the production NMS)
    suggested_keep: list[int]
    summary: str


@router.post("/diagnose/worn", response_model=WornInspectResponse)
async def diagnose_worn_outfit(file: UploadFile = File(...)):
    """
    READ-ONLY. Runs the worn-outfit prompt at box=0.25, then for every raw
    detection crops the region, runs FashionCLIP, and returns:
      - DINO label + confidence
      - FashionCLIP category / subtype / confidence
      - bbox area as fraction of image (small = fragment)
      - overlap with other detections (high = likely duplicate)
      - a "keep / likely-duplicate / likely-fragment" verdict
      - base64 JPEG of each crop for visual inspection
      - an annotated copy of the full image

    Nothing is persisted. No R2, no Redis, no Postgres writes.

    Example:
        curl -s -X POST http://localhost:8000/scan/diagnose/worn \\
             -F "file=@outfit.jpg" | python3 -m json.tool > worn_result.json
        python3 -c "
        import json, base64
        d = json.load(open('worn_result.json'))
        open('annotated.jpg','wb').write(base64.b64decode(d['annotated_jpeg_b64']))
        for c in d['crops']:
            open(f'crop_{c[\"index\"]}_{c[\"fashionclip_subtype\"]}.jpg','wb').write(
                base64.b64decode(c['crop_jpeg_b64']))
        "
    """
    import base64
    from PIL import ImageDraw, ImageFont
    from app.services.detector import (
        _grounding_dino_model as _dino_model,
        _grounding_dino_processor as _dino_proc,
        resize_for_inference, _DINO_MAX_SIDE,
    )
    from app.models.schemas import BoundingBox

    try:
        contents = await file.read()
        image = Image.open(io.BytesIO(contents)).convert("RGB")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid image: {e}")

    if not detector.is_loaded():
        raise HTTPException(status_code=503, detail="Grounding-DINO model not loaded")

    import torch
    BOX_T, TEXT_T = 0.25, 0.20
    PROMPT = _WORN_OUTFIT_PROMPT

    inf_img = resize_for_inference(image, _DINO_MAX_SIDE)
    device = next(_dino_model.parameters()).device
    inputs = _dino_proc(images=inf_img, text=PROMPT, return_tensors="pt").to(device)
    t0 = time.perf_counter()
    with torch.no_grad():
        outputs = _dino_model(**inputs)
    logger.info(f"timing worn-inspect-forward: {(time.perf_counter()-t0)*1000:.0f}ms")

    results = _dino_proc.post_process_grounded_object_detection(
        outputs, inputs.input_ids,
        box_threshold=BOX_T, text_threshold=TEXT_T,
        target_sizes=[inf_img.size[::-1]],
    )[0]

    iw, ih = inf_img.size
    raw_dets = []
    for box, score, label in zip(results["boxes"], results["scores"], results["labels"]):
        x1, y1, x2, y2 = box.tolist()
        raw_dets.append({
            "label": str(label).strip(),
            "conf":  round(float(score), 4),
            "x_min": round(max(0.0, x1 / iw), 4),
            "y_min": round(max(0.0, y1 / ih), 4),
            "x_max": round(min(1.0, x2 / iw), 4),
            "y_max": round(min(1.0, y2 / ih), 4),
        })
    raw_dets.sort(key=lambda d: d["conf"], reverse=True)

    def _iou(a: dict, b: dict) -> float:
        ix1 = max(a["x_min"], b["x_min"]); iy1 = max(a["y_min"], b["y_min"])
        ix2 = min(a["x_max"], b["x_max"]); iy2 = min(a["y_max"], b["y_max"])
        inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
        if inter == 0.0:
            return 0.0
        aa = (a["x_max"]-a["x_min"]) * (a["y_max"]-a["y_min"])
        ab = (b["x_max"]-b["x_min"]) * (b["y_max"]-b["y_min"])
        return inter / (aa + ab - inter)

    OVERLAP_FLAG_THRESHOLD = 0.30   # IoU above this → flag as overlapping
    FRAGMENT_AREA_MAX = 0.04        # boxes covering <4% of image → flag as fragment

    # Build overlap map
    n = len(raw_dets)
    overlaps: list[list[int]] = [[] for _ in range(n)]
    for i in range(n):
        for j in range(i + 1, n):
            if _iou(raw_dets[i], raw_dets[j]) >= OVERLAP_FLAG_THRESHOLD:
                overlaps[i].append(j)
                overlaps[j].append(i)

    crops: list[CropInspection] = []
    full_w, full_h = image.size
    COLORS = ["#FF3300","#0055FF","#00AA44","#FF8800","#AA00FF","#00CCCC","#FF0088"]

    for idx, det in enumerate(raw_dets):
        bbox = BoundingBox(
            x_min=det["x_min"], y_min=det["y_min"],
            x_max=det["x_max"], y_max=det["y_max"],
        )
        area_frac = round((det["x_max"]-det["x_min"]) * (det["y_max"]-det["y_min"]), 4)

        crop_img = segmenter.segment_crop(image, bbox)

        if embedder.is_loaded():
            fc_cat, fc_sub, fc_conf = embedder.classify_subtype(crop_img, det["label"])
            fc_cat_str = fc_cat.value
        else:
            fc_cat_str, fc_sub, fc_conf = "UNKNOWN", det["label"], 0.0

        # Verdict heuristic — for inspection only, not production filtering
        if area_frac < FRAGMENT_AREA_MAX:
            verdict = "likely-fragment"
        elif overlaps[idx]:
            # If a higher-confidence detection already covers this region, flag as duplicate
            dominated = any(
                raw_dets[j]["conf"] > det["conf"]
                for j in overlaps[idx]
            )
            verdict = "likely-duplicate" if dominated else "keep"
        else:
            verdict = "keep"

        buf = io.BytesIO()
        crop_img.convert("RGB").save(buf, format="JPEG", quality=80)
        crop_b64 = base64.b64encode(buf.getvalue()).decode()

        crops.append(CropInspection(
            index=idx,
            dino_label=det["label"],
            dino_conf=det["conf"],
            bbox={"x_min": det["x_min"], "y_min": det["y_min"],
                  "x_max": det["x_max"], "y_max": det["y_max"]},
            area_fraction=area_frac,
            fashionclip_category=fc_cat_str,
            fashionclip_subtype=fc_sub,
            fashionclip_conf=round(fc_conf, 4),
            overlaps_with=overlaps[idx],
            verdict=verdict,
            crop_jpeg_b64=crop_b64,
        ))

    # Annotated full image
    ann = image.copy()
    draw = ImageDraw.Draw(ann)
    for idx, det in enumerate(raw_dets):
        c = crops[idx]
        color = COLORS[idx % len(COLORS)]
        x1 = int(det["x_min"] * full_w); y1 = int(det["y_min"] * full_h)
        x2 = int(det["x_max"] * full_w); y2 = int(det["y_max"] * full_h)
        width = 4 if c.verdict == "keep" else 2
        draw.rectangle([x1, y1, x2, y2], outline=color, width=width)
        label_text = (
            f"#{idx} {c.fashionclip_subtype} {c.fashionclip_conf:.2f}"
            f" [{c.verdict}]"
        )
        draw.rectangle([x1, y1, x1 + len(label_text)*7, y1 + 16], fill=color)
        draw.text((x1 + 2, y1 + 1), label_text, fill="white")

    ann_buf = io.BytesIO()
    ann.save(ann_buf, format="JPEG", quality=85)
    ann_b64 = base64.b64encode(ann_buf.getvalue()).decode()

    suggested_keep = [c.index for c in crops if c.verdict == "keep"]
    keep_summary = ", ".join(
        f"#{c.index} {c.fashionclip_subtype}({c.fashionclip_conf:.2f})"
        for c in crops if c.verdict == "keep"
    ) or "none"
    summary = (
        f"{len(raw_dets)} raw detections; {len(suggested_keep)} flagged keep: {keep_summary}. "
        f"Duplicates/fragments: {len(raw_dets)-len(suggested_keep)}. "
        "Verdicts are heuristic — review crops to confirm before any production change."
    )

    return WornInspectResponse(
        image_width=image.width,
        image_height=image.height,
        prompt_used=PROMPT,
        box_threshold=BOX_T,
        text_threshold=TEXT_T,
        raw_count=len(raw_dets),
        crops=crops,
        annotated_jpeg_b64=ann_b64,
        suggested_keep=suggested_keep,
        summary=summary,
    )


class SubjectFilterDetection(BaseModel):
    index: int
    dino_label: str
    dino_conf: float
    bbox: dict
    area_fraction: float
    # Fraction of THIS garment box that lies inside the primary-person box
    containment_in_subject: float
    fashionclip_category: str = ""
    fashionclip_subtype: str = ""
    fashionclip_conf: float = 0.0
    kept: bool = False
    reject_reason: str = ""           # "" when kept; else "fragment" / "outside-subject" / "no-subject"


class SubjectFilterResponse(BaseModel):
    image_width: int
    image_height: int
    garment_prompt: str
    person_prompt: str
    box_threshold: float
    # The chosen primary-person bbox (largest by area), or null if none found
    primary_subject_bbox: Optional[dict] = None
    primary_subject_area: float = 0.0
    detections: list[SubjectFilterDetection]
    kept_indices: list[int]
    annotated_before_b64: str         # all raw garment boxes
    annotated_after_b64: str          # only kept boxes + subject box
    summary: str


# Prompt to locate the primary human subject of the photo.
_PERSON_PROMPT = "person . man . woman"

# Minimum garment-box area (fraction of image) to be considered a real garment,
# not a tiny background fragment. The two real garments in the gym selfie were
# ~0.083–0.091; the rejected background fragments were 0.002–0.005.
_MIN_GARMENT_AREA = 0.02
# Minimum fraction of a garment box that must lie inside the primary-person box.
_MIN_CONTAINMENT = 0.55


@router.post("/diagnose/subject", response_model=SubjectFilterResponse)
async def diagnose_subject_filter(file: UploadFile = File(...)):
    """
    READ-ONLY. Experimental main-subject garment filter for worn-outfit photos.

    Pipeline:
      1. DINO with the worn-outfit garment prompt (box=0.25) → candidate garments
      2. DINO with a person prompt → choose the LARGEST person box as the primary
         subject (the person taking the selfie / standing in front)
      3. Keep a garment only if:
           - its area >= _MIN_GARMENT_AREA (drops tiny background fragments), AND
           - >= _MIN_CONTAINMENT of its box lies inside the primary-person box
             (drops clothing on other people / mirror reflections / background)
      4. Run FashionCLIP only on the KEPT garments
      5. Return before/after annotated images so the filter can be verified

    Nothing is persisted. No R2/Redis/Postgres writes. Production pipeline is
    NOT changed by calling this.

    Example:
        curl -s -X POST http://localhost:8000/scan/diagnose/subject \\
             -F "file=@outfit.jpg" > subject_result.json
        python3 tests/extract_subject_filter.py subject_result.json ./out/
    """
    import base64
    from PIL import ImageDraw
    from app.services.detector import (
        _grounding_dino_model as _dino_model,
        _grounding_dino_processor as _dino_proc,
        resize_for_inference, _DINO_MAX_SIDE,
    )
    from app.models.schemas import BoundingBox

    try:
        contents = await file.read()
        image = Image.open(io.BytesIO(contents)).convert("RGB")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid image: {e}")

    if not detector.is_loaded():
        raise HTTPException(status_code=503, detail="Grounding-DINO model not loaded")

    import torch
    BOX_T, TEXT_T = 0.25, 0.20
    inf_img = resize_for_inference(image, _DINO_MAX_SIDE)
    device = next(_dino_model.parameters()).device
    iw, ih = inf_img.size
    full_w, full_h = image.size

    def _run(prompt: str, box_t: float, text_t: float) -> list[dict]:
        inputs = _dino_proc(images=inf_img, text=prompt, return_tensors="pt").to(device)
        t0 = time.perf_counter()
        with torch.no_grad():
            outputs = _dino_model(**inputs)
        logger.info(f"timing subject-filter-forward: {(time.perf_counter()-t0)*1000:.0f}ms  '{prompt[:30]}'")
        results = _dino_proc.post_process_grounded_object_detection(
            outputs, inputs.input_ids,
            box_threshold=box_t, text_threshold=text_t,
            target_sizes=[inf_img.size[::-1]],
        )[0]
        out = []
        for box, score, label in zip(results["boxes"], results["scores"], results["labels"]):
            x1, y1, x2, y2 = box.tolist()
            out.append({
                "label": str(label).strip(), "conf": round(float(score), 4),
                "x_min": round(max(0.0, x1/iw), 4), "y_min": round(max(0.0, y1/ih), 4),
                "x_max": round(min(1.0, x2/iw), 4), "y_max": round(min(1.0, y2/ih), 4),
            })
        out.sort(key=lambda d: d["conf"], reverse=True)
        return out

    def _area(b: dict) -> float:
        return max(0.0, b["x_max"]-b["x_min"]) * max(0.0, b["y_max"]-b["y_min"])

    def _containment(garment: dict, person: dict) -> float:
        """Fraction of *garment* area that lies inside *person*."""
        ix1 = max(garment["x_min"], person["x_min"]); iy1 = max(garment["y_min"], person["y_min"])
        ix2 = min(garment["x_max"], person["x_max"]); iy2 = min(garment["y_max"], person["y_max"])
        inter = max(0.0, ix2-ix1) * max(0.0, iy2-iy1)
        ga = _area(garment)
        return inter / ga if ga > 0 else 0.0

    # ── 1. Garment candidates ─────────────────────────────────────────────────
    garments = _run(_WORN_OUTFIT_PROMPT, BOX_T, TEXT_T)

    # ── 2. Primary subject: largest person box ────────────────────────────────
    persons = _run(_PERSON_PROMPT, 0.30, 0.25)
    primary = max(persons, key=_area) if persons else None

    # ── 3. Filter ─────────────────────────────────────────────────────────────
    detections: list[SubjectFilterDetection] = []
    kept_raw: list[dict] = []
    for idx, g in enumerate(garments):
        area = round(_area(g), 4)
        contain = round(_containment(g, primary), 4) if primary else 0.0

        if area < _MIN_GARMENT_AREA:
            kept, reason = False, "fragment"
        elif primary is None:
            kept, reason = False, "no-subject"
        elif contain < _MIN_CONTAINMENT:
            kept, reason = False, "outside-subject"
        else:
            kept, reason = True, ""

        det = SubjectFilterDetection(
            index=idx, dino_label=g["label"], dino_conf=g["conf"],
            bbox={k: g[k] for k in ("x_min", "y_min", "x_max", "y_max")},
            area_fraction=area, containment_in_subject=contain,
            kept=kept, reject_reason=reason,
        )
        if kept:
            kept_raw.append(g)
        detections.append(det)

    # ── 4. FashionCLIP on kept garments only ──────────────────────────────────
    for det in detections:
        if not det.kept:
            continue
        bbox = BoundingBox(**det.bbox)
        crop = segmenter.segment_crop(image, bbox)
        if embedder.is_loaded():
            cat, sub, conf = embedder.classify_subtype(crop, det.dino_label)
            det.fashionclip_category = cat.value
            det.fashionclip_subtype = sub
            det.fashionclip_conf = round(conf, 4)
        else:
            det.fashionclip_category = "UNKNOWN"
            det.fashionclip_subtype = det.dino_label
            det.fashionclip_conf = 0.0

    # ── 5. Before/after annotated images ──────────────────────────────────────
    def _annotate(raw_boxes: list[dict], dets: list, draw_subject: bool) -> str:
        img = image.copy()
        d = ImageDraw.Draw(img)
        if draw_subject and primary is not None:
            px1 = int(primary["x_min"]*full_w); py1 = int(primary["y_min"]*full_h)
            px2 = int(primary["x_max"]*full_w); py2 = int(primary["y_max"]*full_h)
            d.rectangle([px1, py1, px2, py2], outline="#FFD000", width=3)
            d.text((px1+2, py1+2), "PRIMARY SUBJECT", fill="#FFD000")
        for idx, g in enumerate(raw_boxes):
            x1 = int(g["x_min"]*full_w); y1 = int(g["y_min"]*full_h)
            x2 = int(g["x_max"]*full_w); y2 = int(g["y_max"]*full_h)
            det = dets[idx] if idx < len(dets) else None
            kept = det.kept if det else True
            color = "#00CC44" if kept else "#FF3300"
            d.rectangle([x1, y1, x2, y2], outline=color, width=4 if kept else 2)
            if det:
                tag = (f"#{idx} {det.fashionclip_subtype or det.dino_label[:14]} "
                       f"{'KEEP' if kept else det.reject_reason}")
                d.rectangle([x1, max(0, y1-16), x1+len(tag)*7, y1], fill=color)
                d.text((x1+2, max(0, y1-15)), tag, fill="white")
        buf = io.BytesIO(); img.save(buf, format="JPEG", quality=85)
        return base64.b64encode(buf.getvalue()).decode()

    before_b64 = _annotate(garments, detections, draw_subject=True)
    after_b64 = _annotate(kept_raw,
                          [d for d in detections if d.kept],
                          draw_subject=True)

    kept_indices = [d.index for d in detections if d.kept]
    kept_desc = ", ".join(
        f"#{d.index} {d.fashionclip_category}/{d.fashionclip_subtype}({d.fashionclip_conf:.2f})"
        for d in detections if d.kept
    ) or "none"
    summary = (
        f"{len(garments)} garment candidates → {len(kept_indices)} kept after subject filter. "
        f"Primary subject: {'found' if primary else 'NOT FOUND'}. "
        f"Kept: {kept_desc}. "
        f"Rejected: {len(garments)-len(kept_indices)} "
        f"({sum(1 for d in detections if d.reject_reason=='fragment')} fragments, "
        f"{sum(1 for d in detections if d.reject_reason=='outside-subject')} outside-subject)."
    )

    return SubjectFilterResponse(
        image_width=image.width, image_height=image.height,
        garment_prompt=_WORN_OUTFIT_PROMPT, person_prompt=_PERSON_PROMPT,
        box_threshold=BOX_T,
        primary_subject_bbox=({k: primary[k] for k in ("x_min","y_min","x_max","y_max")}
                              if primary else None),
        primary_subject_area=round(_area(primary), 4) if primary else 0.0,
        detections=detections,
        kept_indices=kept_indices,
        annotated_before_b64=before_b64,
        annotated_after_b64=after_b64,
        summary=summary,
    )


@router.post("/upload", response_model=ScanResponse)
async def scan_uploaded_image(file: UploadFile = File(...)):
    """
    Accept a direct image upload (for testing), run detection, return results.
    The image is also uploaded to R2 for downstream use.
    """
    try:
        contents = await file.read()
        image = Image.open(io.BytesIO(contents)).convert("RGB")
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid image: {e}")

    try:
        image_key = _r2.upload_image(image, folder="scans/direct")
    except Exception:
        image_key = f"scans/direct/{uuid.uuid4()}.jpg"

    detections = detector.detect_clothing(image)

    return ScanResponse(
        job_id=str(uuid.uuid4()),
        detections=detections,
        image_width=image.width,
        image_height=image.height,
    )
