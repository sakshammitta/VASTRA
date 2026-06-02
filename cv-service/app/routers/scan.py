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
