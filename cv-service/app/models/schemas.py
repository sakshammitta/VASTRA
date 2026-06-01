from pydantic import BaseModel, Field
from typing import Optional
from enum import Enum


class ClothingCategory(str, Enum):
    TOP = "TOP"
    BOTTOM = "BOTTOM"
    OUTERWEAR = "OUTERWEAR"
    FOOTWEAR = "FOOTWEAR"
    ACCESSORY = "ACCESSORY"
    BAG = "BAG"
    DRESS = "DRESS"
    SUIT = "SUIT"
    OTHER = "OTHER"


class BoundingBox(BaseModel):
    x_min: float = Field(..., ge=0.0, le=1.0, description="Normalized left coordinate")
    y_min: float = Field(..., ge=0.0, le=1.0, description="Normalized top coordinate")
    x_max: float = Field(..., ge=0.0, le=1.0, description="Normalized right coordinate")
    y_max: float = Field(..., ge=0.0, le=1.0, description="Normalized bottom coordinate")


class Detection(BaseModel):
    label: str
    confidence: float
    bbox: BoundingBox


class ScanRequest(BaseModel):
    image_key: str = Field(..., description="Cloudflare R2 object key of the uploaded image")


class ScanResponse(BaseModel):
    job_id: str
    detections: list[Detection]
    image_width: int
    image_height: int


class SegmentRequest(BaseModel):
    image_key: str
    detections: list[Detection]


class SegmentedCrop(BaseModel):
    detection: Detection
    crop_key: Optional[str] = None
    crop_base64: Optional[str] = None
    mask_applied: bool = False


class SegmentResponse(BaseModel):
    crops: list[SegmentedCrop]


class EmbedRequest(BaseModel):
    image_key: str
    detections: list[Detection]


class DetectedItem(BaseModel):
    detection: Detection
    # None when FashionCLIP is not loaded — stored as NULL in pgvector column.
    # A null embedding is explicitly absent; a zero vector would be misleading.
    embedding: Optional[list[float]] = Field(None, description="512-dim FashionCLIP vector; null when unavailable")
    color_palette: list[str] = Field(..., description="Top-3 hex colors from K-means in LAB space")
    category: ClothingCategory
    sub_category: str = ""
    # FashionCLIP subtype-classification confidence in [0,1]; 0.0 means the
    # subtype was inferred from the detection label (unverified, user should confirm).
    subtype_confidence: float = 0.0
    crop_key: Optional[str] = None


class EmbedResponse(BaseModel):
    items: list[DetectedItem]


class HealthResponse(BaseModel):
    status: str                          # "ok" | "degraded" | "mock"
    mock_allowed: bool
    models_loaded: dict[str, bool]
    detail: str = ""
