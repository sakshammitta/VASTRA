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
    scan_mode: str = "outfit"  # "single" | "outfit"


@router.post("/full", response_model=EmbedResponse)
async def scan_and_embed(request: ScanAndEmbedRequest):
    """
    Single-round-trip endpoint: fetch the image from R2 once, run the full
    pipeline (DINO detection → crop → FashionCLIP classify + embed → colors),
    and return EmbedResponse items.  Called by the backend for every app scan.

    scan_mode="outfit" (default): detect_worn_outfit() — all garments.
    scan_mode="single": detect_single_item() — dominant/centred garment only,
      dropping border-touching boxes and keeping only the top area×centrality item.
    """
    t0_total = time.perf_counter()

    try:
        t0 = time.perf_counter()
        image = _r2.download_image(request.image_key)
        logger.info(f"timing r2-fetch: {(time.perf_counter() - t0)*1000:.0f}ms  {image.width}x{image.height}")
    except r2_module.R2DownloadError as e:
        # 404 only for genuinely-missing objects; 502/503 for auth/network/config
        # so the client message reflects the real cause instead of "not found".
        status = 404 if e.kind == "not_found" else 422 if e.kind == "not_image" else 502
        logger.error(f"/embed/full r2-fetch failed kind={e.kind}: {e}")
        raise HTTPException(status_code=status, detail=str(e))
    except Exception as e:
        logger.error(f"/embed/full r2-fetch unexpected error: {e}")
        raise HTTPException(status_code=502, detail=f"Image fetch failed: {e}")

    t0 = time.perf_counter()
    if request.scan_mode == "single":
        detections = detector.detect_single_item(image)
    else:
        detections = detector.detect_worn_outfit(image)
    logger.info(f"timing dino-inference: {(time.perf_counter() - t0)*1000:.0f}ms  scan_mode={request.scan_mode} detections={len(detections)}")

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
    except r2_module.R2DownloadError as e:
        status = 404 if e.kind == "not_found" else 422 if e.kind == "not_image" else 502
        logger.error(f"/embed/whole r2-fetch failed kind={e.kind}: {e}")
        raise HTTPException(status_code=status, detail=str(e))
    except Exception as e:
        logger.error(f"/embed/whole r2-fetch unexpected error: {e}")
        raise HTTPException(status_code=502, detail=f"Image fetch failed: {e}")

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
            crop = segmenter.segment_crop(image, detection.bbox, detection_label=detection.label)
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
    except r2_module.R2DownloadError as e:
        status = 404 if e.kind == "not_found" else 422 if e.kind == "not_image" else 502
        logger.error(f"/embed r2-fetch failed kind={e.kind}: {e}")
        raise HTTPException(status_code=status, detail=str(e))
    except Exception as e:
        logger.error(f"/embed r2-fetch unexpected error: {e}")
        raise HTTPException(status_code=502, detail=f"Image fetch failed: {e}")

    items = _embed_detections(image, request.detections)
    logger.info(f"timing embed-total: {(time.perf_counter() - t0_total)*1000:.0f}ms  items={len(items)}")
    return EmbedResponse(items=items)


class EmbedUrlRequest(BaseModel):
    image_url: str


class EmbedUrlResponse(BaseModel):
    # Normalized FashionCLIP embedding for the downloaded image, or null when
    # the model is not loaded or the image could not be fetched/decoded. The
    # caller treats null as "cannot validate visually" (does NOT fabricate a match).
    embedding: list[float] | None = None


@router.post("/url", response_model=EmbedUrlResponse)
def embed_url(request: EmbedUrlRequest):
    """
    Download an arbitrary image URL (e.g. a web-match candidate product thumbnail)
    and return its FashionCLIP embedding. Used by the backend to validate that a
    web-match candidate is visually similar to the user's original item crop —
    same type + same color is not enough; the silhouette/details must match too.

    Returns embedding=null (HTTP 200) when FashionCLIP is unavailable or the image
    cannot be fetched/decoded. The backend then skips visual validation for that
    candidate rather than guessing.
    """
    import io
    import urllib.request
    import urllib.error
    from PIL import Image as PilImage

    url = request.image_url
    logger.info(f"/embed/url: fetching {url[:120]}")

    if not embedder.is_loaded():
        logger.info("/embed/url: FashionCLIP not loaded — returning null embedding")
        return EmbedUrlResponse(embedding=None)

    try:
        t0 = time.perf_counter()
        req = urllib.request.Request(
            url,
            headers={
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                              "(KHTML, like Gecko) Chrome/124.0 Safari/537.36",
                "Accept": "image/webp,image/apng,image/*,*/*;q=0.8",
            },
        )
        with urllib.request.urlopen(req, timeout=12) as resp:
            content_type = resp.headers.get("Content-Type", "")
            content_length = resp.headers.get("Content-Length", "?")
            raw = resp.read()

        magic = raw[:16].hex() if len(raw) >= 4 else "short"
        logger.info(
            f"/embed/url: fetched bytes={len(raw)} content-type={content_type!r} "
            f"content-length={content_length} magic={magic} url={url[:80]}"
        )

        # Reject HTML/JSON bodies (e.g. login wall, CDN error page)
        ct_lower = content_type.lower()
        if "html" in ct_lower or "json" in ct_lower or "text" in ct_lower:
            logger.warning(f"/embed/url: non-image content-type {content_type!r} for {url[:80]}")
            return EmbedUrlResponse(embedding=None)

        # Reject suspiciously small payloads (< 500 bytes cannot be a real image)
        if len(raw) < 500:
            logger.warning(f"/embed/url: payload too small ({len(raw)} bytes) for {url[:80]}")
            return EmbedUrlResponse(embedding=None)

        try:
            image = PilImage.open(io.BytesIO(raw)).convert("RGB")
        except Exception as pil_err:
            logger.warning(f"/embed/url: PIL.open failed ({pil_err}) magic={magic} for {url[:80]}")
            return EmbedUrlResponse(embedding=None)

        embedding = embedder.get_embedding(image)
        logger.info(
            f"timing embed-url: {(time.perf_counter() - t0)*1000:.0f}ms  "
            f"size={image.width}x{image.height} present={embedding is not None}"
        )
        return EmbedUrlResponse(embedding=embedding)

    except urllib.error.HTTPError as e:
        logger.warning(f"/embed/url: HTTP {e.code} for {url[:80]}")
        return EmbedUrlResponse(embedding=None)
    except urllib.error.URLError as e:
        logger.warning(f"/embed/url: URL error ({e.reason}) for {url[:80]}")
        return EmbedUrlResponse(embedding=None)
    except Exception as e:
        logger.warning(f"/embed/url: unexpected error ({type(e).__name__}: {e}) for {url[:80]}")
        return EmbedUrlResponse(embedding=None)
