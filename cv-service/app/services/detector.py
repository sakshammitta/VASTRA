import os
import logging
from typing import Optional
import numpy as np
from PIL import Image

from app.models.schemas import BoundingBox, Detection

logger = logging.getLogger(__name__)

CLOTHING_PROMPT = "clothing . shirt . pants . jacket . shoes . bag . dress . coat . skirt . shorts . sweater . hoodie . blazer . suit"

_grounding_dino_model = None
_grounding_dino_processor = None
_models_loaded = False


def load_models():
    global _grounding_dino_model, _grounding_dino_processor, _models_loaded
    if _models_loaded:
        return
    try:
        from transformers import AutoProcessor, AutoModelForZeroShotObjectDetection
        import torch

        model_id = os.getenv("GROUNDING_DINO_MODEL", "IDEA-Research/grounding-dino-base")
        device = "cuda" if torch.cuda.is_available() else "cpu"
        logger.info(f"Loading Grounding-DINO on {device}...")
        _grounding_dino_processor = AutoProcessor.from_pretrained(model_id)
        _grounding_dino_model = AutoModelForZeroShotObjectDetection.from_pretrained(model_id).to(device)
        _models_loaded = True
        logger.info("Grounding-DINO loaded successfully")
    except Exception as e:
        logger.warning(f"Could not load Grounding-DINO: {e}. Using mock detector.")
        _models_loaded = False


def is_loaded() -> bool:
    return _models_loaded


def detect_clothing(image: Image.Image, confidence_threshold: float = 0.3) -> list[Detection]:
    """Run Grounding-DINO on image and return clothing detections."""
    if not _models_loaded:
        return _mock_detections(image)

    try:
        import torch
        device = next(_grounding_dino_model.parameters()).device
        inputs = _grounding_dino_processor(
            images=image,
            text=CLOTHING_PROMPT,
            return_tensors="pt"
        ).to(device)

        with torch.no_grad():
            outputs = _grounding_dino_model(**inputs)

        results = _grounding_dino_processor.post_process_grounded_object_detection(
            outputs,
            inputs.input_ids,
            box_threshold=confidence_threshold,
            text_threshold=0.25,
            target_sizes=[image.size[::-1]],
        )[0]

        w, h = image.size
        detections = []
        for box, score, label in zip(results["boxes"], results["scores"], results["labels"]):
            x_min, y_min, x_max, y_max = box.tolist()
            detections.append(Detection(
                label=label,
                confidence=float(score),
                bbox=BoundingBox(
                    x_min=max(0.0, x_min / w),
                    y_min=max(0.0, y_min / h),
                    x_max=min(1.0, x_max / w),
                    y_max=min(1.0, y_max / h),
                )
            ))

        detections.sort(key=lambda d: d.confidence, reverse=True)
        return detections[:10]

    except Exception as e:
        logger.error(f"Grounding-DINO inference failed: {e}")
        return _mock_detections(image)


def _mock_detections(image: Image.Image) -> list[Detection]:
    """Return plausible mock detections for development without GPU."""
    w, h = image.size
    return [
        Detection(
            label="shirt",
            confidence=0.92,
            bbox=BoundingBox(x_min=0.15, y_min=0.1, x_max=0.85, y_max=0.55)
        ),
        Detection(
            label="pants",
            confidence=0.88,
            bbox=BoundingBox(x_min=0.2, y_min=0.5, x_max=0.8, y_max=0.95)
        ),
    ]
