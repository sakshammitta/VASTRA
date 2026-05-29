import logging
import os
import numpy as np
from PIL import Image

from app.models.schemas import BoundingBox, Detection

logger = logging.getLogger(__name__)

_sam_predictor = None
_models_loaded = False


def load_models():
    global _sam_predictor, _models_loaded
    if _models_loaded:
        return
    try:
        from segment_anything import SamPredictor, sam_model_registry
        import torch

        checkpoint = os.getenv("SAM_CHECKPOINT", "sam_vit_b_01ec64.pth")
        model_type = os.getenv("SAM_MODEL_TYPE", "vit_b")
        device = "cuda" if torch.cuda.is_available() else "cpu"

        if not os.path.exists(checkpoint):
            logger.warning(f"SAM checkpoint not found at {checkpoint}. Using crop fallback.")
            return

        logger.info(f"Loading SAM ({model_type}) on {device}...")
        sam = sam_model_registry[model_type](checkpoint=checkpoint)
        sam.to(device=device)
        _sam_predictor = SamPredictor(sam)
        _models_loaded = True
        logger.info("SAM loaded successfully")
    except Exception as e:
        logger.warning(f"Could not load SAM: {e}. Using crop fallback.")


def is_loaded() -> bool:
    return _models_loaded


def segment_crop(image: Image.Image, bbox: BoundingBox) -> Image.Image:
    """
    Segment the clothing item within the bounding box using SAM.
    Falls back to simple crop if SAM is unavailable.
    """
    if not _models_loaded:
        return _crop_fallback(image, bbox)

    try:
        import torch
        img_array = np.array(image)
        _sam_predictor.set_image(img_array)

        w, h = image.size
        box_pixel = np.array([
            bbox.x_min * w, bbox.y_min * h,
            bbox.x_max * w, bbox.y_max * h
        ])

        masks, scores, _ = _sam_predictor.predict(
            box=box_pixel[None, :],
            multimask_output=False
        )

        mask = masks[0]
        rgba = image.convert("RGBA")
        mask_img = Image.fromarray((mask * 255).astype(np.uint8), mode="L")
        rgba.putalpha(mask_img)

        x1, y1, x2, y2 = (int(bbox.x_min * w), int(bbox.y_min * h),
                          int(bbox.x_max * w), int(bbox.y_max * h))
        cropped = rgba.crop((x1, y1, x2, y2))

        result = Image.new("RGB", cropped.size, (255, 255, 255))
        result.paste(cropped, mask=cropped.split()[3])
        return result

    except Exception as e:
        logger.error(f"SAM segmentation failed: {e}")
        return _crop_fallback(image, bbox)


def _crop_fallback(image: Image.Image, bbox: BoundingBox) -> Image.Image:
    w, h = image.size
    x1, y1 = int(bbox.x_min * w), int(bbox.y_min * h)
    x2, y2 = int(bbox.x_max * w), int(bbox.y_max * h)
    padding = 10
    x1, y1 = max(0, x1 - padding), max(0, y1 - padding)
    x2, y2 = min(w, x2 + padding), min(h, y2 + padding)
    return image.crop((x1, y1, x2, y2))
