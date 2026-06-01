"""
Tests for the controlled-taxonomy classification logic.

The label-fallback path (FashionCLIP not loaded) must map Grounding-DINO
labels into the controlled taxonomy and never crash. The FashionCLIP path is
covered by the real-image tests when the model is available.
"""
import sys
from unittest.mock import patch

from PIL import Image

import app.services.embedder as embedder
from app.models.schemas import ClothingCategory


class TestLabelToTaxonomy:
    def test_tshirt_variants_map_to_top(self):
        for label in ["t-shirt", "t - shirt", "tshirt", "shirt", "top", "blouse"]:
            cat, sub = embedder._label_to_taxonomy(label)
            assert cat == ClothingCategory.TOP, f"{label} → {cat}"

    def test_bottoms_map_to_bottom(self):
        for label in ["jeans", "trousers", "pants", "shorts", "skirt"]:
            cat, _ = embedder._label_to_taxonomy(label)
            assert cat == ClothingCategory.BOTTOM, f"{label} → {cat}"

    def test_outerwear_map(self):
        for label in ["jacket", "coat", "blazer"]:
            cat, _ = embedder._label_to_taxonomy(label)
            assert cat == ClothingCategory.OUTERWEAR, f"{label} → {cat}"

    def test_subtype_is_within_taxonomy_or_passthrough(self):
        cat, sub = embedder._label_to_taxonomy("jeans")
        assert sub in embedder._SUBTYPES

    def test_unknown_label_is_other(self):
        cat, sub = embedder._label_to_taxonomy("spaceship")
        assert cat == ClothingCategory.OTHER


class TestClassifySubtypeFallback:
    """With FashionCLIP unloaded, classify_subtype returns confidence 0.0."""

    def setup_method(self):
        for key in list(sys.modules):
            if "app.services.embedder" in key:
                del sys.modules[key]
        with patch.dict("os.environ", {"CV_ALLOW_MOCK": "false"}):
            import importlib
            import app.services.embedder as emb
            importlib.reload(emb)
            self.emb = emb

    def test_returns_zero_confidence_when_unloaded(self):
        assert not self.emb.is_loaded()
        img = Image.new("RGB", (64, 64), (30, 30, 30))
        cat, sub, conf = self.emb.classify_subtype(img, "t-shirt")
        assert conf == 0.0, "Unverified prediction must report confidence 0.0"
        assert cat == ClothingCategory.TOP
        assert sub == "t-shirt"

    def test_taxonomy_covers_required_subtypes(self):
        required = {
            "t-shirt", "shirt", "polo shirt", "hoodie", "sweater",
            "jacket", "coat", "jeans", "trousers", "shorts", "skirt", "dress",
        }
        missing = required - set(self.emb._SUBTYPES)
        assert not missing, f"Taxonomy missing required subtypes: {missing}"
