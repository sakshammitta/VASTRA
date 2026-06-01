import os
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.models.schemas import HealthResponse
from app.routers import scan, segment, embed
from app.services import detector, segmenter, embedder

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s — %(message)s")
logger = logging.getLogger(__name__)

_ALLOW_MOCK: bool = os.getenv("CV_ALLOW_MOCK", "false").lower() == "true"


@asynccontextmanager
async def lifespan(app: FastAPI):
    if _ALLOW_MOCK:
        logger.warning(
            "CV_ALLOW_MOCK=true — mock detections enabled. "
            "Must NOT be used in production."
        )
    logger.info("Loading CV models…")
    detector.load_models()
    segmenter.load_models()
    embedder.load_models()

    dino_ok = detector.is_loaded()
    fc_ok = embedder.is_loaded()

    if dino_ok:
        logger.info("Grounding-DINO loaded — real garment detection is active.")
    elif _ALLOW_MOCK:
        logger.warning("Grounding-DINO not loaded; mock detections will be served (CV_ALLOW_MOCK=true).")
    else:
        logger.error(
            "Grounding-DINO not loaded and CV_ALLOW_MOCK=false. "
            "/scan will return empty detections."
        )

    if not fc_ok:
        logger.info(
            "FashionCLIP not loaded — embeddings will be null, "
            "categories inferred from Grounding-DINO label text."
        )

    logger.info(
        f"CV service ready  grounding_dino={dino_ok}  sam={segmenter.is_loaded()}  "
        f"fashion_clip={fc_ok}  mock_allowed={_ALLOW_MOCK}"
    )
    yield
    logger.info("CV service shutting down")


app = FastAPI(
    title="Vastra CV Service",
    description=(
        "Clothing detection (Grounding-DINO), segmentation (SAM), "
        "and embedding (FashionCLIP). "
        "Detection, embedding, and segmentation are independent capabilities; "
        "each degrades gracefully when unavailable."
    ),
    version="1.0.0",
    lifespan=lifespan,
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(scan.router)
app.include_router(segment.router)
app.include_router(embed.router)


@app.get("/health", response_model=HealthResponse)
async def health():
    dino_ok = detector.is_loaded()
    fc_ok = embedder.is_loaded()
    sam_ok = segmenter.is_loaded()

    models_loaded = {
        "grounding_dino": dino_ok,
        "sam": sam_ok,
        "fashion_clip": fc_ok,
    }

    if _ALLOW_MOCK and not dino_ok:
        status = "mock"
        detail = (
            "CV_ALLOW_MOCK=true and Grounding-DINO not loaded. "
            "Returning labelled [MOCK] detections. Not for production."
        )
    elif dino_ok and fc_ok:
        status = "ok"
        detail = "All models loaded. Detection and embedding fully operational."
    elif dino_ok and not fc_ok:
        # This is the current real state: detection works, embeddings not yet available.
        status = "detection_only"
        detail = (
            "Grounding-DINO loaded — real garment detection is active. "
            "FashionCLIP not loaded — embeddings stored as null; "
            "categories inferred from detection label text. "
            f"{'SAM not loaded — using bounding-box crop.' if not sam_ok else 'SAM loaded.'}"
        )
    elif not dino_ok:
        status = "degraded"
        detail = (
            "Grounding-DINO not loaded — /scan returns empty detections "
            "(CV_ALLOW_MOCK=false)."
        )
    else:
        status = "degraded"
        detail = "Unexpected model state."

    return HealthResponse(
        status=status,
        mock_allowed=_ALLOW_MOCK,
        models_loaded=models_loaded,
        detail=detail,
    )
