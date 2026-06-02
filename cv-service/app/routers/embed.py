import logging
import time
from fastapi import APIRouter, HTTPException

from app.models.schemas import EmbedRequest, EmbedResponse, DetectedItem
from app.services import segmenter, embedder, r2_client as r2_module

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/embed", tags=["embed"])

_r2 = r2_module.R2Client()


@router.post("", response_model=EmbedResponse)
async def embed_detections(request: EmbedRequest):
    """
    For each detection bounding box:
    1. Crop the item (SAM segmentation when loaded, bbox crop otherwise)
    2. Extract dominant colors via K-means in LAB space (always available)
    3. Infer clothing category:
       - FashionCLIP text-image similarity when loaded (accurate)
       - Heuristic label match from Grounding-DINO label text (interim)
    4. Compute 512-dim FashionCLIP embedding when loaded; null otherwise.
       A null embedding is stored as NULL in the wardrobe DB — never as a
       zero vector, which would produce misleading similarity results.
    """
    t0_total = time.perf_counter()
    try:
        t0 = time.perf_counter()
        image = _r2.download_image(request.image_key)
        logger.info(f"timing r2-fetch: {(time.perf_counter() - t0)*1000:.0f}ms  {image.width}x{image.height}")
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Image not found: {request.image_key}")

    items = []
    for detection in request.detections:
        try:
            # Segmentation: SAM when loaded, bbox crop otherwise.
            t0 = time.perf_counter()
            crop = segmenter.segment_crop(image, detection.bbox)
            logger.info(f"timing segment-crop: {(time.perf_counter() - t0)*1000:.0f}ms")

            # Colors: K-means in LAB space — always available, no ML model needed.
            colors = embedder.extract_colors(crop, k=3)

            # Subtype + category: FashionCLIP zero-shot against the controlled
            # taxonomy when loaded (the real fashion classifier), else best-effort
            # mapping from the Grounding-DINO label (confidence 0.0 = unverified).
            t0 = time.perf_counter()
            category, sub_category, subtype_conf = embedder.classify_subtype(
                crop, detection.label
            )
            logger.info(
                f"timing fashionclip-classify: {(time.perf_counter() - t0)*1000:.0f}ms  "
                f"subtype={sub_category} conf={subtype_conf:.3f}"
            )

            # Embedding: FashionCLIP when loaded, None otherwise.
            # The schema and backend both accept None; it maps to NULL in pgvector.
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

    logger.info(f"timing embed-total: {(time.perf_counter() - t0_total)*1000:.0f}ms  items={len(items)}")

    return EmbedResponse(items=items)
