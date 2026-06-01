import os
import logging
from typing import Optional
import numpy as np
from PIL import Image

from app.models.schemas import BoundingBox, Detection

logger = logging.getLogger(__name__)

# When CV_ALLOW_MOCK is not explicitly "true", the service must NEVER fabricate
# detections. With real models unavailable and mock disabled, detect_clothing()
# returns an empty list so the caller sees "no items detected" — an honest state.
_ALLOW_MOCK: bool = os.getenv("CV_ALLOW_MOCK", "false").lower() == "true"

CLOTHING_PROMPT = (
    "shirt . t-shirt . blouse . top . sweater . hoodie . "
    "pants . trousers . jeans . shorts . skirt . "
    "jacket . coat . blazer . "
    "dress . jumpsuit . "
    "shoes . boots . sneakers . "
    "bag . handbag . backpack . "
    "hat . scarf . belt"
)

_grounding_dino_model = None
_grounding_dino_processor = None
_models_loaded = False


def load_models() -> None:
    global _grounding_dino_model, _grounding_dino_processor, _models_loaded
    if _models_loaded:
        return
    try:
        from transformers import AutoProcessor, AutoModelForZeroShotObjectDetection
        import torch

        model_id = os.getenv("GROUNDING_DINO_MODEL", "IDEA-Research/grounding-dino-base")
        device = "cuda" if torch.cuda.is_available() else "cpu"
        logger.info(f"Loading Grounding-DINO ({model_id}) on {device}…")
        _grounding_dino_processor = AutoProcessor.from_pretrained(model_id)
        _grounding_dino_model = (
            AutoModelForZeroShotObjectDetection.from_pretrained(model_id).to(device)
        )
        _models_loaded = True
        logger.info("Grounding-DINO loaded successfully")
    except Exception as exc:
        if _ALLOW_MOCK:
            logger.warning(f"Grounding-DINO unavailable ({exc}). Mock mode enabled.")
        else:
            logger.error(
                f"Grounding-DINO unavailable ({exc}). "
                "CV_ALLOW_MOCK=false → will return empty detections."
            )
        _models_loaded = False


def is_loaded() -> bool:
    return _models_loaded


def detect_clothing(
    image: Image.Image,
    box_threshold: float = 0.35,
    text_threshold: float = 0.25,
) -> list[Detection]:
    """
    Run Grounding-DINO on *image* and return deduplicated clothing detections.

    If the model is not loaded:
    - CV_ALLOW_MOCK=true  → return labelled mock detections (dev only).
    - CV_ALLOW_MOCK=false → return [] (honest: no detection possible).
    """
    if not _models_loaded:
        if _ALLOW_MOCK:
            logger.debug("Mock detector active — returning labelled demo detections.")
            return _mock_detections(image)
        logger.warning("Grounding-DINO not loaded; returning empty detections.")
        return []

    try:
        import torch

        device = next(_grounding_dino_model.parameters()).device
        inputs = _grounding_dino_processor(
            images=image,
            text=CLOTHING_PROMPT,
            return_tensors="pt",
        ).to(device)

        with torch.no_grad():
            outputs = _grounding_dino_model(**inputs)

        results = _grounding_dino_processor.post_process_grounded_object_detection(
            outputs,
            inputs.input_ids,
            box_threshold=box_threshold,
            text_threshold=text_threshold,
            target_sizes=[image.size[::-1]],
        )[0]

        w, h = image.size
        raw: list[Detection] = []
        for box, score, label in zip(
            results["boxes"], results["scores"], results["labels"]
        ):
            x_min, y_min, x_max, y_max = box.tolist()
            raw.append(
                Detection(
                    label=str(label).strip(),
                    confidence=float(score),
                    bbox=BoundingBox(
                        x_min=max(0.0, x_min / w),
                        y_min=max(0.0, y_min / h),
                        x_max=min(1.0, x_max / w),
                        y_max=min(1.0, y_max / h),
                    ),
                )
            )

        raw.sort(key=lambda d: d.confidence, reverse=True)
        return _deduplicate(raw)[:10]

    except Exception as exc:
        logger.error(f"Grounding-DINO inference failed: {exc}")
        return []


# ── Post-processing ───────────────────────────────────────────────────────────

def _iou(a: Detection, b: Detection) -> float:
    """Intersection-over-union of two normalised bounding boxes."""
    ix1 = max(a.bbox.x_min, b.bbox.x_min)
    iy1 = max(a.bbox.y_min, b.bbox.y_min)
    ix2 = min(a.bbox.x_max, b.bbox.x_max)
    iy2 = min(a.bbox.y_max, b.bbox.y_max)
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    if inter == 0.0:
        return 0.0
    area_a = (a.bbox.x_max - a.bbox.x_min) * (a.bbox.y_max - a.bbox.y_min)
    area_b = (b.bbox.x_max - b.bbox.x_min) * (b.bbox.y_max - b.bbox.y_min)
    return inter / (area_a + area_b - inter)


# Generic catch-all labels emitted by Grounding-DINO that should be
# superseded by any specific label that overlaps significantly.
_GENERIC_LABELS = {"clothing", "garment", "outfit", "apparel", "item"}

def _deduplicate(detections: list[Detection], iou_threshold: float = 0.5) -> list[Detection]:
    """
    NMS-style deduplication.  Iterates highest-confidence first; suppresses
    any later box whose IoU with an already-kept box exceeds *iou_threshold*,
    UNLESS the kept box is a generic label and the later box is specific
    (in which case the generic box is replaced).
    """
    kept: list[Detection] = []
    for det in detections:  # already sorted high→low confidence
        suppress = False
        for i, k in enumerate(kept):
            if _iou(det, k) >= iou_threshold:
                # Replace generic with specific
                if k.label.lower() in _GENERIC_LABELS and det.label.lower() not in _GENERIC_LABELS:
                    kept[i] = det
                suppress = True
                break
        if not suppress:
            kept.append(det)
    return kept


# ── Mock (dev only, CV_ALLOW_MOCK=true) ──────────────────────────────────────

def _mock_detections(image: Image.Image) -> list[Detection]:
    """
    Clearly-labelled mock for offline UI development.
    Only active when CV_ALLOW_MOCK=true is set explicitly.
    Returns a single generic item so developers see the flow without fake garment data.
    """
    return [
        Detection(
            label="[MOCK] garment",
            confidence=0.50,
            bbox=BoundingBox(x_min=0.1, y_min=0.1, x_max=0.9, y_max=0.9),
        )
    ]
