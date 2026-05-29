import logging
import io
import base64
import numpy as np
from PIL import Image
from typing import Optional

from app.models.schemas import ClothingCategory

logger = logging.getLogger(__name__)

_fashion_clip_model = None
_fashion_clip_preprocess = None
_models_loaded = False

_CATEGORY_LABELS = [
    "top", "shirt", "t-shirt", "blouse", "sweater", "hoodie",
    "pants", "trousers", "jeans", "shorts", "skirt",
    "dress", "jumpsuit",
    "jacket", "coat", "blazer", "outerwear",
    "shoes", "boots", "sneakers", "footwear",
    "bag", "purse", "backpack", "handbag",
    "accessory", "hat", "belt", "scarf",
    "suit",
]

_LABEL_TO_CATEGORY = {
    "top": ClothingCategory.TOP, "shirt": ClothingCategory.TOP,
    "t-shirt": ClothingCategory.TOP, "blouse": ClothingCategory.TOP,
    "sweater": ClothingCategory.TOP, "hoodie": ClothingCategory.TOP,
    "pants": ClothingCategory.BOTTOM, "trousers": ClothingCategory.BOTTOM,
    "jeans": ClothingCategory.BOTTOM, "shorts": ClothingCategory.BOTTOM,
    "skirt": ClothingCategory.BOTTOM,
    "dress": ClothingCategory.DRESS, "jumpsuit": ClothingCategory.DRESS,
    "jacket": ClothingCategory.OUTERWEAR, "coat": ClothingCategory.OUTERWEAR,
    "blazer": ClothingCategory.OUTERWEAR, "outerwear": ClothingCategory.OUTERWEAR,
    "shoes": ClothingCategory.FOOTWEAR, "boots": ClothingCategory.FOOTWEAR,
    "sneakers": ClothingCategory.FOOTWEAR, "footwear": ClothingCategory.FOOTWEAR,
    "bag": ClothingCategory.BAG, "purse": ClothingCategory.BAG,
    "backpack": ClothingCategory.BAG, "handbag": ClothingCategory.BAG,
    "accessory": ClothingCategory.ACCESSORY, "hat": ClothingCategory.ACCESSORY,
    "belt": ClothingCategory.ACCESSORY, "scarf": ClothingCategory.ACCESSORY,
    "suit": ClothingCategory.SUIT,
}


def load_models():
    global _fashion_clip_model, _fashion_clip_preprocess, _models_loaded
    if _models_loaded:
        return
    try:
        from fashion_clip.fashion_clip import FashionCLIP
        logger.info("Loading FashionCLIP...")
        fc = FashionCLIP("fashion-clip")
        _fashion_clip_model = fc
        _models_loaded = True
        logger.info("FashionCLIP loaded successfully")
    except Exception as e:
        logger.warning(f"Could not load FashionCLIP: {e}. Using mock embedder.")


def is_loaded() -> bool:
    return _models_loaded


def get_embedding(image: Image.Image) -> list[float]:
    """Return 512-dim FashionCLIP embedding for an image crop."""
    if not _models_loaded:
        return _mock_embedding()
    try:
        embeddings = _fashion_clip_model.encode_images([image], batch_size=1)
        vec = embeddings[0]
        norm = np.linalg.norm(vec)
        if norm > 0:
            vec = vec / norm
        return vec.tolist()
    except Exception as e:
        logger.error(f"FashionCLIP embedding failed: {e}")
        return _mock_embedding()


def classify_category(image: Image.Image, detection_label: str) -> tuple[ClothingCategory, str]:
    """
    Classify the clothing category using FashionCLIP text-image similarity.
    Falls back to heuristic label matching.
    """
    if not _models_loaded:
        return _heuristic_category(detection_label)

    try:
        text_embeddings = _fashion_clip_model.encode_text(_CATEGORY_LABELS, batch_size=32)
        img_embedding = np.array(get_embedding(image))
        similarities = text_embeddings @ img_embedding
        best_idx = int(np.argmax(similarities))
        best_label = _CATEGORY_LABELS[best_idx]
        category = _LABEL_TO_CATEGORY.get(best_label, ClothingCategory.OTHER)
        return category, best_label
    except Exception as e:
        logger.error(f"Category classification failed: {e}")
        return _heuristic_category(detection_label)


def extract_colors(image: Image.Image, k: int = 3) -> list[str]:
    """
    Extract top-k dominant colors using K-means in LAB color space.
    Returns list of hex strings like ['#E8D5C4', '#2B1B0E', '#F5F0EB'].
    """
    try:
        from sklearn.cluster import KMeans
        from skimage import color as skcolor

        img = image.convert("RGB").resize((150, 150))
        pixels = np.array(img).reshape(-1, 3).astype(np.float32) / 255.0
        lab_pixels = skcolor.rgb2lab(pixels.reshape(1, -1, 3)).reshape(-1, 3)

        kmeans = KMeans(n_clusters=k, n_init=10, random_state=42)
        kmeans.fit(lab_pixels)

        centers_lab = kmeans.cluster_centers_.reshape(1, -1, 3)
        centers_rgb = skcolor.lab2rgb(centers_lab).reshape(-1, 3)

        counts = np.bincount(kmeans.labels_, minlength=k)
        sorted_idx = np.argsort(-counts)
        centers_rgb = centers_rgb[sorted_idx]

        return [rgb_to_hex(r, g, b) for r, g, b in centers_rgb]

    except Exception as e:
        logger.error(f"Color extraction failed: {e}")
        return _dominant_color_fallback(image, k)


def rgb_to_hex(r: float, g: float, b: float) -> str:
    ri, gi, bi = int(r * 255), int(g * 255), int(b * 255)
    return f"#{ri:02X}{gi:02X}{bi:02X}"


def _dominant_color_fallback(image: Image.Image, k: int) -> list[str]:
    img = image.convert("RGB").resize((50, 50))
    pixels = list(img.getdata())
    total = len(pixels)
    avg_r = sum(p[0] for p in pixels) // total
    avg_g = sum(p[1] for p in pixels) // total
    avg_b = sum(p[2] for p in pixels) // total
    return [f"#{avg_r:02X}{avg_g:02X}{avg_b:02X}"] * k


def _mock_embedding() -> list[float]:
    rng = np.random.default_rng(42)
    vec = rng.normal(0, 1, 512).astype(np.float32)
    vec = vec / np.linalg.norm(vec)
    return vec.tolist()


def _heuristic_category(label: str) -> tuple[ClothingCategory, str]:
    label_lower = label.lower()
    for key, cat in _LABEL_TO_CATEGORY.items():
        if key in label_lower:
            return cat, key
    return ClothingCategory.OTHER, label_lower
