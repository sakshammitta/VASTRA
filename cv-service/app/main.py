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
            "CV_ALLOW_MOCK=true — mock detections are enabled. "
            "This must NOT be used in production."
        )
    logger.info("Loading CV models…")
    detector.load_models()
    segmenter.load_models()
    embedder.load_models()

    dino_ok = detector.is_loaded()
    fc_ok = embedder.is_loaded()
    if not dino_ok:
        if _ALLOW_MOCK:
            logger.warning("Grounding-DINO not loaded. Mock detections will be served (CV_ALLOW_MOCK=true).")
        else:
            logger.error(
                "Grounding-DINO not loaded and CV_ALLOW_MOCK=false. "
                "/scan will return empty detections until the model is available."
            )
    logger.info(f"CV service ready  grounding_dino={dino_ok}  fashion_clip={fc_ok}  mock_allowed={_ALLOW_MOCK}")
    yield
    logger.info("CV service shutting down")


app = FastAPI(
    title="Vastra CV Service",
    description=(
        "Clothing detection (Grounding-DINO), segmentation (SAM), "
        "and embedding (FashionCLIP). "
        "When CV_ALLOW_MOCK=false (default) and models are not loaded, "
        "the service returns empty detections rather than fabricated data."
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
    all_ok = dino_ok and fc_ok

    if _ALLOW_MOCK and not dino_ok:
        status = "mock"
        detail = "Running with mock detections (CV_ALLOW_MOCK=true). Not suitable for production."
    elif all_ok:
        status = "ok"
        detail = "All models loaded."
    else:
        missing = [name for name, ok in [("grounding_dino", dino_ok), ("fashion_clip", fc_ok)] if not ok]
        status = "degraded"
        detail = f"Models not loaded: {', '.join(missing)}. Scans will return empty detections."

    return HealthResponse(
        status=status,
        mock_allowed=_ALLOW_MOCK,
        models_loaded={"grounding_dino": dino_ok, "sam": sam_ok, "fashion_clip": fc_ok},
        detail=detail,
    )
