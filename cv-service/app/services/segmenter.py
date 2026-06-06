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


def segment_crop(
    image: Image.Image,
    bbox: BoundingBox,
    detection_label: str = "",
) -> Image.Image:
    """
    Segment the clothing item within the bounding box using SAM.
    Falls back to simple crop if SAM is unavailable.

    detection_label is used only in the fallback path to add extra padding
    for outerwear categories (jacket/coat/blazer), which DINO tends to
    tightly box — cutting off lapels, cuffs and hemlines that are essential
    for Google Lens to recognise the garment type correctly.
    """
    if not _models_loaded:
        return _crop_fallback(image, bbox, detection_label=detection_label)

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
        return _crop_fallback(image, bbox, detection_label=detection_label)


def _is_outerwear_label(label: str) -> bool:
    """Jacket/coat/blazer crops need extra padding: DINO tends to clip lapels and cuffs."""
    norm = label.lower()
    return any(kw in norm for kw in ("jacket", "coat", "blazer", "outerwear",
                                      "nehru", "sherwani", "ethnic jacket"))


def _crop_fallback(
    image: Image.Image,
    bbox: BoundingBox,
    detection_label: str = "",
) -> Image.Image:
    """
    Simple bbox crop with padding.

    Outerwear gets larger padding because DINO bboxes for jackets/coats in
    worn-outfit photos are often tight around the torso and clip the lapels,
    cuffs, and hem. A tighter crop hides the structural cues (long sleeves,
    open-front, lapels) that distinguish a jacket from a vest or hoodie —
    Google Lens then returns wrong products and the AI render cannot tell it
    is a jacket. For an OPEN jacket the sleeves extend to the sides while DINO
    often boxes only the torso, so we pad HORIZONTALLY more (12%, where the
    sleeves live) than vertically (6%) to recover the sleeve/lapel silhouette
    without dragging in the legs/background below.
    """
    w, h = image.size

    if _is_outerwear_label(detection_label):
        bw = (bbox.x_max - bbox.x_min) * w
        bh = (bbox.y_max - bbox.y_min) * h
        pad_x = max(20, int(bw * 0.12))   # wider: capture open-jacket sleeves
        pad_y = max(15, int(bh * 0.06))   # modest vertical to avoid legs/background
    else:
        pad_x = pad_y = 10

    x1 = max(0, int(bbox.x_min * w) - pad_x)
    y1 = max(0, int(bbox.y_min * h) - pad_y)
    x2 = min(w, int(bbox.x_max * w) + pad_x)
    y2 = min(h, int(bbox.y_max * h) + pad_y)

    logger.debug(
        f"crop_fallback: label='{detection_label}' "
        f"bbox=({bbox.x_min:.2f},{bbox.y_min:.2f},{bbox.x_max:.2f},{bbox.y_max:.2f}) "
        f"pad=({pad_x},{pad_y}) → px ({x1},{y1},{x2},{y2})"
    )
    return image.crop((x1, y1, x2, y2))
