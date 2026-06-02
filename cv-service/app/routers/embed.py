import logging
import time
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel

from app.models.schemas import (
    EmbedRequest, EmbedResponse, DetectedItem, Detection, BoundingBox,
)
from app.services import detector, segmenter, embedder, r2_client as r2_module

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/embed", tags=["embed"])

_r2 = r2_module.R2Client()


class ScanAndEmbedRequest(BaseModel):
    image_key: str


@router.post("/full", response_model=EmbedResponse)
async def scan_and_embed(request: ScanAndEmbedRequest):
    """
    Single-round-trip endpoint: fetch the image from R2 once, run the full
    pipeline (DINO detection → crop → FashionCLIP classify + embed → colors),
    and return EmbedResponse items.  The backend calls this instead of the
    separate /scan + /embed pair, saving one R2 fetch per scan job.
    """
    t0_total = time.perf_counter()

    try:
        t0 = time.perf_counter()
        image = _r2.download_image(request.image_key)
        logger.info(f"timing r2-fetch: {(time.perf_counter() - t0)*1000:.0f}ms  {image.width}x{image.height}")
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Image not found: {request.image_key}")

    t0 = time.perf_counter()
    detections = detector.detect_clothing(image)
    logger.info(f"timing dino-inference: {(time.perf_counter() - t0)*1000:.0f}ms  detections={len(detections)}")

    items = _embed_detections(image, detections)
    logger.info(f"timing full-pipeline: {(time.perf_counter() - t0_total)*1000:.0f}ms  items={len(items)}")
    return EmbedResponse(items=items)


@router.post("/whole", response_model=EmbedResponse)
async def classify_whole_image(request: ScanAndEmbedRequest):
    """
    FAST single-item path: skip Grounding-DINO entirely and run FashionCLIP on
    the whole uploaded image. Intended for the Scan Closet flow, which instructs
    the user to frame ONE item on a plain background — localization is
    unnecessary there. Returns a single DetectedItem covering the full frame.

    Use /embed/full (DINO + FashionCLIP) for multi-item / full-outfit photos
    where localization is actually needed.
    """
    t0_total = time.perf_counter()

    try:
        t0 = time.perf_counter()
        image = _r2.download_image(request.image_key)
        logger.info(f"timing r2-fetch: {(time.perf_counter() - t0)*1000:.0f}ms  {image.width}x{image.height}")
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Image not found: {request.image_key}")

    # Synthetic full-frame detection so the response shape matches /embed/full.
    full_frame = Detection(
        label="whole-image",
        confidence=1.0,
        bbox=BoundingBox(x_min=0.0, y_min=0.0, x_max=1.0, y_max=1.0),
    )
    items = _embed_detections(image, [full_frame])
    logger.info(f"timing whole-image-pipeline: {(time.perf_counter() - t0_total)*1000:.0f}ms  items={len(items)}")
    return EmbedResponse(items=items)


def _embed_detections(image, detections: list) -> list[DetectedItem]:
    """Crop, classify, embed and color-extract each detection from *image*."""
    from PIL import Image as PilImage
    items = []
    for detection in detections:
        try:
            t0 = time.perf_counter()
            crop = segmenter.segment_crop(image, detection.bbox)
            logger.info(f"timing segment-crop: {(time.perf_counter() - t0)*1000:.0f}ms")

            colors = embedder.extract_colors(crop, k=3)

            t0 = time.perf_counter()
            category, sub_category, subtype_conf = embedder.classify_subtype(
                crop, detection.label
            )
            logger.info(
                f"timing fashionclip-classify: {(time.perf_counter() - t0)*1000:.0f}ms  "
                f"subtype={sub_category} conf={subtype_conf:.3f}"
            )

            t0 = time.perf_counter()
            embedding = embedder.get_embedding(crop)
            logger.info(
                f"timing fashionclip-embed: {(time.perf_counter() - t0)*1000:.0f}ms  "
                f"present={embedding is not None}"
            )

            crop_key = None
            try:
                t0 = time.perf_counter()
                crop_key = _r2.upload_image(crop, folder="item-crops")
                logger.info(f"timing r2-crop-upload: {(time.perf_counter() - t0)*1000:.0f}ms  key={crop_key}")
            except Exception:
                pass

            items.append(DetectedItem(
                detection=detection,
                embedding=embedding,
                color_palette=colors,
                category=category,
                sub_category=sub_category,
                subtype_confidence=subtype_conf,
                crop_key=crop_key,
            ))
        except Exception as e:
            logger.error(f"Failed to process detection '{detection.label}': {e}")
    return items


@router.post("", response_model=EmbedResponse)
async def embed_detections(request: EmbedRequest):
    """
    Embed a list of detections already produced by /scan.
    Kept for backward compatibility; prefer /embed/full for new work.
    """
    t0_total = time.perf_counter()
    try:
        t0 = time.perf_counter()
        image = _r2.download_image(request.image_key)
        logger.info(f"timing r2-fetch: {(time.perf_counter() - t0)*1000:.0f}ms  {image.width}x{image.height}")
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Image not found: {request.image_key}")

    items = _embed_detections(image, request.detections)
    logger.info(f"timing embed-total: {(time.perf_counter() - t0_total)*1000:.0f}ms  items={len(items)}")
    return EmbedResponse(items=items)
