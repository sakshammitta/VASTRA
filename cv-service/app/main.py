import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.models.schemas import HealthResponse
from app.routers import scan, segment, embed
from app.services import detector, segmenter, embedder

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s — %(message)s")
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("Loading CV models...")
    detector.load_models()
    segmenter.load_models()
    embedder.load_models()
    logger.info("CV service ready")
    yield
    logger.info("CV service shutting down")


app = FastAPI(
    title="Vastra CV Service",
    description="Computer vision microservice: clothing detection (Grounding-DINO), segmentation (SAM), and embedding (FashionCLIP)",
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
    return HealthResponse(
        status="ok",
        models_loaded={
            "grounding_dino": detector.is_loaded(),
            "sam": segmenter.is_loaded(),
            "fashion_clip": embedder.is_loaded(),
        },
    )
