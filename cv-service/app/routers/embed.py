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
    1. Crop the item (SAM segmentation when loaded, bbox crop otherwise)
    2. Extract dominant colors via K-means in LAB space (always available)
    3. Infer clothing category:
       - FashionCLIP text-image similarity when loaded (accurate)
       - Heuristic label match from Grounding-DINO label text (interim)
    4. Compute 512-dim FashionCLIP embedding when loaded; null otherwise.
       A null embedding is stored as NULL in the wardrobe DB — never as a
       zero vector, which would produce misleading similarity results.
    """
    try:
        image = _r2.download_image(request.image_key)
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Image not found: {request.image_key}")

    items = []
    for detection in request.detections:
        try:
            # Segmentation: SAM when loaded, bbox crop otherwise.
            crop = segmenter.segment_crop(image, detection.bbox)

            # Colors: K-means in LAB space — always available, no ML model needed.
            colors = embedder.extract_colors(crop, k=3)

            # Subtype + category: FashionCLIP zero-shot against the controlled
            # taxonomy when loaded (the real fashion classifier), else best-effort
            # mapping from the Grounding-DINO label (confidence 0.0 = unverified).
            category, sub_category, subtype_conf = embedder.classify_subtype(
                crop, detection.label
            )

            # Embedding: FashionCLIP when loaded, None otherwise.
            # The schema and backend both accept None; it maps to NULL in pgvector.
            embedding = embedder.get_embedding(crop)

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
                subtype_confidence=subtype_conf,
                crop_key=crop_key,
            ))
        except Exception as e:
            logger.error(f"Failed to process detection '{detection.label}': {e}")

    return EmbedResponse(items=items)
