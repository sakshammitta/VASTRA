import uuid
import logging
import time
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


class DiagnoseResponse(BaseModel):
    image_width: int
    image_height: int
    # Each key is "box=X text=Y"; value is the raw (pre-NMS) detections at that level.
    threshold_sweep: dict[str, list[RawDetection]]
    # Final post-NMS result at production thresholds (box=0.35 text=0.25).
    production_detections: list[Detection]
    # Human-readable one-line diagnosis.
    diagnosis: str


@router.post("/diagnose", response_model=DiagnoseResponse)
async def diagnose_outfit_detection(file: UploadFile = File(...)):
    """
    READ-ONLY diagnostic endpoint. Runs Grounding-DINO at four threshold levels
    and returns ALL raw boxes before NMS/deduplication, plus the production
    pipeline result. Use this to distinguish between:
      - model sees detections but production threshold (0.35) is too strict
      - model genuinely produces zero detections at all threshold levels

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

    import torch
    from app.services.detector import (
        _grounding_dino_model as _dino_model,
        _grounding_dino_processor as _dino_proc,
        resize_for_inference, _DINO_MAX_SIDE,
    )

    if not detector.is_loaded():
        raise HTTPException(status_code=503, detail="Grounding-DINO model not loaded")

    threshold_levels = [
        (0.35, 0.25),
        (0.25, 0.20),
        (0.15, 0.10),
        (0.10, 0.05),
    ]

    inf_img = resize_for_inference(image, _DINO_MAX_SIDE)
    device = next(_dino_model.parameters()).device
    inputs = _dino_proc(
        images=inf_img,
        text=detector.CLOTHING_PROMPT,
        return_tensors="pt",
    ).to(device)

    t0 = time.perf_counter()
    with torch.no_grad():
        outputs = _dino_model(**inputs)
    logger.info(f"timing diagnose-dino-forward: {(time.perf_counter() - t0)*1000:.0f}ms")

    w, h = inf_img.size
    sweep: dict[str, list[RawDetection]] = {}

    for box_t, text_t in threshold_levels:
        results = _dino_proc.post_process_grounded_object_detection(
            outputs,
            inputs.input_ids,
            box_threshold=box_t,
            text_threshold=text_t,
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
        sweep[f"box={box_t}_text={text_t}"] = raw

    production_dets = detector.detect_clothing(image)

    # Derive a one-line diagnosis
    prod_raw = sweep["box=0.35_text=0.25"]
    if prod_raw:
        diagnosis = f"Production threshold sufficient: {len(prod_raw)} raw boxes, {len(production_dets)} after NMS"
    elif sweep["box=0.25_text=0.20"]:
        best = sweep["box=0.25_text=0.20"][0]
        diagnosis = (
            f"Detections exist below production threshold — top conf={best.conf:.3f} at box=0.25. "
            "Recommend lowering box_threshold to 0.25 for outfit photos."
        )
    elif sweep["box=0.15_text=0.10"]:
        best = sweep["box=0.15_text=0.10"][0]
        diagnosis = (
            f"Very low confidence detections only (top={best.conf:.3f} at box=0.15). "
            "Prompt change likely needed for this photo type."
        )
    elif sweep["box=0.10_text=0.05"]:
        diagnosis = (
            "Near-floor detections only. Model is not recognising clothing in this image reliably."
        )
    else:
        diagnosis = "ZERO detections at all threshold levels. Model produced nothing for this image."

    return DiagnoseResponse(
        image_width=image.width,
        image_height=image.height,
        threshold_sweep=sweep,
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
