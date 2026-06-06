import os
import logging
import time
from typing import Optional
import numpy as np
from PIL import Image

from app.models.schemas import BoundingBox, Detection

logger = logging.getLogger(__name__)

# When CV_ALLOW_MOCK is not explicitly "true", the service must NEVER fabricate
# detections. With real models unavailable and mock disabled, detect_clothing()
# returns an empty list so the caller sees "no items detected" — an honest state.
_ALLOW_MOCK: bool = os.getenv("CV_ALLOW_MOCK", "false").lower() == "true"

# Maximum longest-edge pixel dimension for the image sent to Grounding-DINO.
# The model operates on a 640-px feature grid regardless of input size;
# passing a 3072×4080 phone photo gives no extra accuracy but adds ~18 s on CPU.
# Bounding boxes are normalized (0-1) so they remain valid for the original image.
# Set DINO_MAX_SIDE=0 to disable resizing and use the original image (old behaviour).
_DINO_MAX_SIDE: int = int(os.getenv("DINO_MAX_SIDE", "1280"))

CLOTHING_PROMPT = (
    "shirt . t-shirt . blouse . top . sweater . hoodie . "
    "pants . trousers . jeans . shorts . skirt . "
    "jacket . coat . blazer . "
    "dress . jumpsuit . "
    "shoes . boots . sneakers . "
    "bag . handbag . backpack . "
    "hat . scarf . belt"
)

# Worn-outfit prompt: "<garment> worn by person" phrasing substantially improves
# detection of clothing on a real person vs the production prompt above which
# was tuned for flat-lay / isolated-item photos.  Diagnostic test result:
#   production prompt  (box=0.35) → 0 detections on gym-selfie outfit photo
#   worn-outfit prompt (box=0.25) → 5 raw detections incl. the correct top+bottom
# Used by detect_worn_outfit() which is the primary production path for the
# outfit-first app flow.  CLOTHING_PROMPT is kept for regression testing via
# /scan/inspect.
WORN_OUTFIT_PROMPT = (
    "shirt worn by person . t-shirt worn by person . "
    "jeans worn by person . trousers worn by person . pants worn by person . "
    "hoodie worn by person . jacket worn by person . "
    "shorts worn by person . skirt worn by person . shoes worn by person"
)

# Minimum garment box area (fraction of full image) to be considered a real
# garment.  Empirical baseline from gym-selfie test:
#   real top:    area ≈ 0.091   real bottom: area ≈ 0.083
#   background fragments: area 0.002–0.005
# Floor of 0.02 drops fragments with a wide safety margin.
_MIN_GARMENT_AREA: float = float(os.getenv("MIN_GARMENT_AREA", "0.02"))

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


def resize_for_inference(image: Image.Image, max_side: int) -> Image.Image:
    """
    Resize *image* so its longest edge is at most *max_side* pixels, preserving
    aspect ratio with high-quality downsampling.  Returns the original if it
    already fits or max_side == 0.

    Because detect_clothing() normalizes bboxes by the image it receives, the
    returned coordinates are identical regardless of whether the original or
    the resized image is passed — callers can always use them against the
    full-resolution original for cropping.
    """
    if max_side <= 0:
        return image
    w, h = image.size
    longest = max(w, h)
    if longest <= max_side:
        return image
    scale = max_side / longest
    new_w, new_h = int(w * scale), int(h * scale)
    logger.info(f"resize_for_inference: {w}x{h} → {new_w}x{new_h} (max_side={max_side})")
    return image.resize((new_w, new_h), Image.LANCZOS)


def detect_clothing(
    image: Image.Image,
    box_threshold: float = 0.35,
    text_threshold: float = 0.25,
) -> list[Detection]:
    """
    Run Grounding-DINO on *image* and return deduplicated clothing detections.

    The image is resized to at most DINO_MAX_SIDE on its longest edge before
    inference.  Bounding boxes are always normalized (0-1) relative to whatever
    image size DINO sees, so they remain valid for the full-resolution original.

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

        inference_image = resize_for_inference(image, _DINO_MAX_SIDE)

        # ── Preprocessing (processor: resize/normalize/tokenize) ──────────────
        t_pre = time.perf_counter()
        device = next(_grounding_dino_model.parameters()).device
        inputs = _grounding_dino_processor(
            images=inference_image,
            text=CLOTHING_PROMPT,
            return_tensors="pt",
        ).to(device)
        pre_ms = (time.perf_counter() - t_pre) * 1000

        # Log the ACTUAL pixel tensor shape entering the model — this reveals
        # whether the processor internally rescales our resized image to a fixed
        # size (in which case manual resize cannot reduce forward-pass cost).
        pv = inputs.get("pixel_values")
        pixel_shape = tuple(pv.shape) if pv is not None else None

        # ── Model forward pass ────────────────────────────────────────────────
        t_fwd = time.perf_counter()
        with torch.no_grad():
            outputs = _grounding_dino_model(**inputs)
        fwd_ms = (time.perf_counter() - t_fwd) * 1000

        # ── Postprocessing (threshold + decode) ───────────────────────────────
        t_post = time.perf_counter()
        results = _grounding_dino_processor.post_process_grounded_object_detection(
            outputs,
            inputs.input_ids,
            box_threshold=box_threshold,
            text_threshold=text_threshold,
            target_sizes=[inference_image.size[::-1]],
        )[0]
        post_ms = (time.perf_counter() - t_post) * 1000

        logger.info(
            f"timing dino-split: preprocess={pre_ms:.0f}ms  forward={fwd_ms:.0f}ms  "
            f"postprocess={post_ms:.0f}ms  pixel_values={pixel_shape}  "
            f"requested_input={inference_image.width}x{inference_image.height}"
        )

        w, h = inference_image.size
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


def detect_worn_outfit(image: Image.Image) -> list[Detection]:
    """
    Primary production path for the outfit-first app flow.

    Uses WORN_OUTFIT_PROMPT ("<garment> worn by person") at box_threshold=0.25
    instead of the CLOTHING_PROMPT/0.35 defaults which produce zero detections
    on real worn-outfit photos (gym-selfie baseline, 2026-06-02).

    Post-processing applied on top of the standard NMS/dedup:
      - drop any box whose normalized area < _MIN_GARMENT_AREA (default 0.02)
        to eliminate tiny background/mirror/reflection fragments
        (baseline: fragments had area 0.002–0.005; real garments 0.083–0.091)

    Falls back to detect_clothing() behaviour when model is not loaded
    (mock or empty list per CV_ALLOW_MOCK setting).
    """
    if not _models_loaded:
        if _ALLOW_MOCK:
            return _mock_detections(image)
        logger.warning("Grounding-DINO not loaded; returning empty detections.")
        return []

    try:
        import torch

        inference_image = resize_for_inference(image, _DINO_MAX_SIDE)

        t_pre = time.perf_counter()
        device = next(_grounding_dino_model.parameters()).device
        inputs = _grounding_dino_processor(
            images=inference_image,
            text=WORN_OUTFIT_PROMPT,
            return_tensors="pt",
        ).to(device)
        pre_ms = (time.perf_counter() - t_pre) * 1000

        pv = inputs.get("pixel_values")
        pixel_shape = tuple(pv.shape) if pv is not None else None

        t_fwd = time.perf_counter()
        with torch.no_grad():
            outputs = _grounding_dino_model(**inputs)
        fwd_ms = (time.perf_counter() - t_fwd) * 1000

        t_post = time.perf_counter()
        results = _grounding_dino_processor.post_process_grounded_object_detection(
            outputs,
            inputs.input_ids,
            box_threshold=0.25,
            text_threshold=0.20,
            target_sizes=[inference_image.size[::-1]],
        )[0]
        post_ms = (time.perf_counter() - t_post) * 1000

        logger.info(
            f"timing worn-dino-split: preprocess={pre_ms:.0f}ms  forward={fwd_ms:.0f}ms  "
            f"postprocess={post_ms:.0f}ms  pixel_values={pixel_shape}"
        )

        w, h = inference_image.size
        raw: list[Detection] = []
        for box, score, label in zip(
            results["boxes"], results["scores"], results["labels"]
        ):
            x_min, y_min, x_max, y_max = box.tolist()
            nx_min = max(0.0, x_min / w)
            ny_min = max(0.0, y_min / h)
            nx_max = min(1.0, x_max / w)
            ny_max = min(1.0, y_max / h)
            area = (nx_max - nx_min) * (ny_max - ny_min)
            if area < _MIN_GARMENT_AREA:
                logger.debug(f"worn-outfit: dropping fragment '{label}' area={area:.4f} < {_MIN_GARMENT_AREA}")
                continue
            raw.append(
                Detection(
                    label=str(label).strip(),
                    confidence=float(score),
                    bbox=BoundingBox(
                        x_min=nx_min, y_min=ny_min,
                        x_max=nx_max, y_max=ny_max,
                    ),
                )
            )

        raw.sort(key=lambda d: d.confidence, reverse=True)
        kept = _deduplicate(raw)[:10]
        logger.info(
            f"worn-outfit: {len(results['boxes'])} raw → "
            f"{len(raw)} after area floor → {len(kept)} after dedup"
        )
        return kept

    except Exception as exc:
        logger.error(f"Grounding-DINO worn-outfit inference failed: {exc}")
        return []


# Border proximity threshold: a box whose edge sits within this fraction of the
# image boundary on any side gets a centrality penalty in detect_single_item()
# (it is a SOFT factor, never a hard exclusion — the main garment in a tight
# close-up legitimately reaches the frame edge and must not be discarded).
_BORDER_MARGIN: float = float(os.getenv("SINGLE_ITEM_BORDER_MARGIN", "0.05"))


def detect_single_item(image: Image.Image) -> list[Detection]:
    """
    Single-item wardrobe scan: run detect_worn_outfit() then return ONLY the one
    dominant garment — the main framed piece — suppressing side/background items.

    Selection is a single global argmax over a dominance score, so it always
    returns exactly one item when anything was detected. Area is the dominant
    term (the main garment is almost always the largest box); centrality and a
    soft border penalty break ties and demote side/background fragments. We do
    NOT hard-filter by border proximity, which could wrongly discard a large
    close-up garment that reaches the frame edge while keeping a small interior
    fragment.

    Falls back to detect_worn_outfit() behaviour if model is not loaded.
    """
    candidates = detect_worn_outfit(image)
    if len(candidates) <= 1:
        return candidates

    def _dominance(d: Detection) -> float:
        b = d.bbox
        area = (b.x_max - b.x_min) * (b.y_max - b.y_min)
        cx = (b.x_min + b.x_max) / 2.0
        cy = (b.y_min + b.y_max) / 2.0
        # Centrality: 1.0 at image centre, → 0 toward edges.
        centrality = max(0.0, (1.0 - abs(cx - 0.5) * 2)) * max(0.0, (1.0 - abs(cy - 0.5) * 2))
        # Soft border penalty: a box hugging the frame edge is more likely a
        # partial/background capture, so shave its score (but never to zero —
        # area still dominates so a large central-ish garment wins).
        touches_border = (b.x_min < _BORDER_MARGIN or b.y_min < _BORDER_MARGIN
                          or b.x_max > 1.0 - _BORDER_MARGIN or b.y_max > 1.0 - _BORDER_MARGIN)
        border_factor = 0.85 if touches_border else 1.0
        # Area-weighted: 0.7 area + 0.3 (area*centrality), then border penalty.
        return (0.7 * area + 0.3 * area * centrality) * border_factor

    best = max(candidates, key=_dominance)
    b = best.bbox
    logger.info(
        f"single-item: {len(candidates)} worn-outfit detections → kept 1 "
        f"('{best.label}', area={(b.x_max - b.x_min)*(b.y_max - b.y_min):.3f}, "
        f"conf={best.confidence:.2f})"
    )
    return [best]


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
