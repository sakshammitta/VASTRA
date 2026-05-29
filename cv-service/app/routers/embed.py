import logging
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
    1. Crop/segment the item from the image
    2. Run FashionCLIP → 512-dim embedding
    3. Run K-means (k=3) in LAB color space → top 3 hex colors
    4. Classify clothing category from FashionCLIP logits
    Returns a full DetectedItem for each bounding box.
    """
    try:
        image = _r2.download_image(request.image_key)
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Image not found: {request.image_key}")

    items = []
    for detection in request.detections:
        try:
            crop = segmenter.segment_crop(image, detection.bbox)

            embedding = embedder.get_embedding(crop)
            colors = embedder.extract_colors(crop, k=3)
            category, sub_category = embedder.classify_category(crop, detection.label)

            crop_key = None
            try:
                crop_key = _r2.upload_image(crop, folder="item-crops")
            except Exception:
                pass

            items.append(DetectedItem(
                detection=detection,
                embedding=embedding,
                color_palette=colors,
                category=category,
                sub_category=sub_category,
                crop_key=crop_key,
            ))
        except Exception as e:
            logger.error(f"Failed to embed detection '{detection.label}': {e}")

    return EmbedResponse(items=items)
