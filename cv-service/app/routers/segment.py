import base64
import io
import logging
from fastapi import APIRouter, HTTPException

from app.models.schemas import SegmentRequest, SegmentResponse, SegmentedCrop
from app.services import segmenter, r2_client as r2_module

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/segment", tags=["segment"])

_r2 = r2_module.R2Client()


@router.post("", response_model=SegmentResponse)
async def segment_detections(request: SegmentRequest):
    """
    Run SAM segmentation on each detected bounding box.
    Returns segmented image crops (uploaded to R2 if configured, else base64).
    """
    try:
        image = _r2.download_image(request.image_key)
    except Exception as e:
        raise HTTPException(status_code=404, detail=f"Image not found: {request.image_key}")

    crops = []
    for detection in request.detections:
        try:
            crop = segmenter.segment_crop(image, detection.bbox)

            crop_key = None
            crop_b64 = None

            try:
                crop_key = _r2.upload_image(crop, folder="crops")
            except Exception:
                buf = io.BytesIO()
                crop.save(buf, format="JPEG", quality=85)
                crop_b64 = base64.b64encode(buf.getvalue()).decode()

            crops.append(SegmentedCrop(
                detection=detection,
                crop_key=crop_key,
                crop_base64=crop_b64,
                mask_applied=segmenter.is_loaded(),
            ))
        except Exception as e:
            logger.error(f"Failed to segment detection '{detection.label}': {e}")
            crops.append(SegmentedCrop(
                detection=detection,
                crop_key=None,
                crop_base64=None,
                mask_applied=False,
            ))

    return SegmentResponse(crops=crops)
