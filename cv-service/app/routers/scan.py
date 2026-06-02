import uuid
import logging
from fastapi import APIRouter, HTTPException, UploadFile, File
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
    try:
        image = _r2.download_image(request.image_key)
    except Exception as e:
        logger.error(f"Failed to download image {request.image_key}: {e}")
        raise HTTPException(status_code=404, detail=f"Image not found: {request.image_key}")

    detections = detector.detect_clothing(image)

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
