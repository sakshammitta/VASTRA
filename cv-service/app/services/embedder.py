import os
import logging
import numpy as np
from PIL import Image

from app.models.schemas import ClothingCategory

logger = logging.getLogger(__name__)

_ALLOW_MOCK: bool = os.getenv("CV_ALLOW_MOCK", "false").lower() == "true"

_fashion_clip_model = None
_models_loaded = False

# ── Controlled fashion subtype taxonomy ───────────────────────────────────────
# FashionCLIP classifies each crop against THIS list (independent of the
# Grounding-DINO phrase). Each subtype maps to a broad ClothingCategory.
_SUBTYPE_TO_CATEGORY: dict[str, ClothingCategory] = {
    "t-shirt": ClothingCategory.TOP,
    "shirt": ClothingCategory.TOP,
    "polo shirt": ClothingCategory.TOP,
    "blouse": ClothingCategory.TOP,
    "tank top": ClothingCategory.TOP,
    "sweater": ClothingCategory.TOP,
    "hoodie": ClothingCategory.TOP,
    "jacket": ClothingCategory.OUTERWEAR,
    "coat": ClothingCategory.OUTERWEAR,
    "blazer": ClothingCategory.OUTERWEAR,
    "jeans": ClothingCategory.BOTTOM,
    "trousers": ClothingCategory.BOTTOM,
    "shorts": ClothingCategory.BOTTOM,
    "skirt": ClothingCategory.BOTTOM,
    "dress": ClothingCategory.DRESS,
    "jumpsuit": ClothingCategory.DRESS,
    "shoes": ClothingCategory.FOOTWEAR,
    "sneakers": ClothingCategory.FOOTWEAR,
    "boots": ClothingCategory.FOOTWEAR,
    "bag": ClothingCategory.BAG,
    "backpack": ClothingCategory.BAG,
    "hat": ClothingCategory.ACCESSORY,
    "scarf": ClothingCategory.ACCESSORY,
    "belt": ClothingCategory.ACCESSORY,
    "suit": ClothingCategory.SUIT,
}

_SUBTYPES: list[str] = list(_SUBTYPE_TO_CATEGORY.keys())

# Prompt template improves FashionCLIP zero-shot accuracy vs bare nouns.
_SUBTYPE_PROMPTS: list[str] = [f"a photo of a {s}" for s in _SUBTYPES]

# Cached text embeddings (computed once after the model loads).
_subtype_text_embeddings = None


def load_models() -> None:
    global _fashion_clip_model, _models_loaded
    if _models_loaded:
        return
    try:
        from fashion_clip.fashion_clip import FashionCLIP
        logger.info("Loading FashionCLIP…")
        _fashion_clip_model = FashionCLIP("fashion-clip")
        _models_loaded = True
        logger.info("FashionCLIP loaded successfully")
    except Exception as exc:
        if _ALLOW_MOCK:
            logger.warning(f"FashionCLIP unavailable ({exc}). Mock embeddings enabled.")
        else:
            logger.info(
                f"FashionCLIP unavailable ({exc}). "
                "Embeddings will be null; subtype falls back to the detection label."
            )
        _models_loaded = False


def is_loaded() -> bool:
    return _models_loaded


def _subtype_texts() -> np.ndarray:
    """Lazily compute + cache FashionCLIP text embeddings for the taxonomy."""
    global _subtype_text_embeddings
    if _subtype_text_embeddings is None:
        embs = _fashion_clip_model.encode_text(_SUBTYPE_PROMPTS, batch_size=32)
        embs = np.asarray(embs, dtype=np.float32)
        # L2-normalize for cosine similarity
        norms = np.linalg.norm(embs, axis=1, keepdims=True)
        _subtype_text_embeddings = embs / np.clip(norms, 1e-8, None)
    return _subtype_text_embeddings


def get_embedding(image: Image.Image) -> list[float] | None:
    """
    Return a normalized 512-dim FashionCLIP image embedding, or None when
    FashionCLIP is not loaded. Callers store None as NULL — never a zero
    vector, which would corrupt similarity search.
    """
    if not _models_loaded:
        if _ALLOW_MOCK:
            return _mock_embedding()
        return None
    try:
        embeddings = _fashion_clip_model.encode_images([image], batch_size=1)
        vec = np.asarray(embeddings[0], dtype=np.float32)
        norm = np.linalg.norm(vec)
        if norm > 0:
            vec = vec / norm
        return vec.tolist()
    except Exception as exc:
        logger.error(f"FashionCLIP embedding failed: {exc}")
        return None


def classify_subtype(
    image: Image.Image,
    detection_label: str,
) -> tuple[ClothingCategory, str, float]:
    """
    Classify a garment crop into the controlled subtype taxonomy.

    Primary path (FashionCLIP loaded): zero-shot image-text similarity against
    _SUBTYPES — this is the real fashion-specific classifier and is what
    distinguishes a t-shirt from a jacket regardless of the DINO phrase.

    Fallback (FashionCLIP not loaded): map the Grounding-DINO detection label
    into the taxonomy. This is best-effort only and may be wrong (e.g. a tee
    detected as "jacket"); the user corrects it in the confirmation UI.

    Returns (category, subtype, confidence). confidence is 0.0 in the fallback
    path to signal the prediction is unverified.
    """
    if _models_loaded:
        try:
            img_emb = np.asarray(get_embedding(image), dtype=np.float32)
            sims = _subtype_texts() @ img_emb            # cosine (both normalized)
            best_idx = int(np.argmax(sims))
            subtype = _SUBTYPES[best_idx]
            category = _SUBTYPE_TO_CATEGORY[subtype]
            confidence = float(sims[best_idx])
            return category, subtype, confidence
        except Exception as exc:
            logger.error(f"FashionCLIP subtype classification failed: {exc}")

    # Fallback: derive subtype from the DINO label, constrained to the taxonomy.
    category, subtype = _label_to_taxonomy(detection_label)
    return category, subtype, 0.0


def _label_to_taxonomy(label: str) -> tuple[ClothingCategory, str]:
    """Map a free-form DINO label to the nearest taxonomy subtype + category."""
    norm = label.lower().replace(" ", "").replace("-", "").strip()
    # Direct / substring match against taxonomy keys
    for subtype, cat in _SUBTYPE_TO_CATEGORY.items():
        key = subtype.lower().replace(" ", "").replace("-", "")
        if key in norm or norm in key:
            return cat, subtype
    # A few common DINO synonyms not spelled exactly like the taxonomy
    synonyms = {
        "top": ("t-shirt", ClothingCategory.TOP),
        "pants": ("trousers", ClothingCategory.BOTTOM),
        "footwear": ("shoes", ClothingCategory.FOOTWEAR),
        "handbag": ("bag", ClothingCategory.BAG),
        "purse": ("bag", ClothingCategory.BAG),
    }
    for syn, (subtype, cat) in synonyms.items():
        if syn in norm:
            return cat, subtype
    return ClothingCategory.OTHER, label.lower().strip()


def extract_colors(image: Image.Image, k: int = 3) -> list[str]:
    """Top-k dominant colors via K-means in LAB color space (no ML model)."""
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

        return [_rgb_to_hex(r, g, b) for r, g, b in centers_rgb]
    except Exception as exc:
        logger.error(f"Color extraction failed: {exc}")
        return _dominant_color_fallback(image, k)


def _rgb_to_hex(r: float, g: float, b: float) -> str:
    return f"#{int(r * 255):02X}{int(g * 255):02X}{int(b * 255):02X}"


def _dominant_color_fallback(image: Image.Image, k: int) -> list[str]:
    img = image.convert("RGB").resize((50, 50))
    pixels = list(img.getdata())
    total = len(pixels)
    avg_r = sum(p[0] for p in pixels) // total
    avg_g = sum(p[1] for p in pixels) // total
    avg_b = sum(p[2] for p in pixels) // total
    return [f"#{avg_r:02X}{avg_g:02X}{avg_b:02X}"] * k


def _mock_embedding() -> list[float]:
    """Fixed random vector — only used when CV_ALLOW_MOCK=true."""
    rng = np.random.default_rng(42)
    vec = rng.normal(0, 1, 512).astype(np.float32)
    vec = vec / np.linalg.norm(vec)
    return vec.tolist()
