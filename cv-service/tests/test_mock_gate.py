"""
Tests for the honest-mock gate (no ML models required).

These must pass before any ML work begins, and must always pass regardless
of whether Grounding-DINO is installed.  They test the contract:

  CV_ALLOW_MOCK=false  →  detect_clothing() returns []   (no fabricated data)
  CV_ALLOW_MOCK=true   →  detect_clothing() returns mock items labelled [MOCK]
"""
import importlib
import sys
from pathlib import Path
from unittest.mock import patch

import pytest
from PIL import Image

FIXTURES = Path(__file__).parent / "fixtures"


def _fresh_detector(allow_mock: bool):
    """Return a freshly-imported detector module with the desired env flag."""
    # Remove cached module so the module-level _ALLOW_MOCK is re-evaluated.
    for key in list(sys.modules):
        if "app.services.detector" in key or key == "detector":
            del sys.modules[key]
    with patch.dict("os.environ", {"CV_ALLOW_MOCK": "true" if allow_mock else "false"}):
        import importlib
        import app.services.detector as det
        importlib.reload(det)
        return det


class TestMockGateFalse:
    """CV_ALLOW_MOCK=false — must never fabricate detections."""

    def setup_method(self):
        self.det = _fresh_detector(allow_mock=False)

    def test_returns_empty_when_model_not_loaded(self):
        """With no model loaded and mock disabled, result must be empty."""
        assert not self.det.is_loaded(), "Expected model to be unloaded in test env"
        img = Image.new("RGB", (64, 64), (200, 200, 200))
        result = self.det.detect_clothing(img)
        assert result == [], f"Expected [], got {result}"

    def test_no_phantom_shirt(self):
        img = Image.open(FIXTURES / "single_shirt.jpg")
        result = self.det.detect_clothing(img)
        assert result == [], "Mock gate failed: phantom shirt returned"

    def test_no_phantom_trousers(self):
        img = Image.open(FIXTURES / "single_trousers.jpg")
        result = self.det.detect_clothing(img)
        assert result == [], "Mock gate failed: phantom trousers returned"

    def test_no_phantom_outfit(self):
        img = Image.open(FIXTURES / "outfit_shirt_trousers.jpg")
        result = self.det.detect_clothing(img)
        assert result == [], "Mock gate failed: phantom outfit returned"


class TestMockGateTrue:
    """CV_ALLOW_MOCK=true — may return mock items, but they must be labelled."""

    def setup_method(self):
        self.det = _fresh_detector(allow_mock=True)

    def test_returns_items_with_mock_label(self):
        img = Image.new("RGB", (64, 64), (200, 200, 200))
        result = self.det.detect_clothing(img)
        assert len(result) >= 1, "Mock mode returned no items"
        for item in result:
            assert "[MOCK]" in item.label, (
                f"Mock item label '{item.label}' must contain '[MOCK]' "
                "so it is never mistaken for a real detection"
            )

    def test_mock_bbox_is_valid(self):
        img = Image.new("RGB", (64, 64), (200, 200, 200))
        for det in self.det.detect_clothing(img):
            b = det.bbox
            assert 0.0 <= b.x_min < b.x_max <= 1.0
            assert 0.0 <= b.y_min < b.y_max <= 1.0


class TestNullEmbeddingContract:
    """
    When FashionCLIP is not loaded and mock is disabled, get_embedding() must
    return None — not a zero vector. A zero vector in pgvector produces
    misleading similarity results.
    """

    def test_get_embedding_returns_none_without_fashionclip(self):
        import sys
        for key in list(sys.modules):
            if "app.services.embedder" in key:
                del sys.modules[key]
        with patch.dict("os.environ", {"CV_ALLOW_MOCK": "false"}):
            import importlib
            import app.services.embedder as emb
            importlib.reload(emb)
            # Model definitely not loaded in test env
            assert not emb.is_loaded()
            img = Image.new("RGB", (64, 64), (200, 200, 200))
            result = emb.get_embedding(img)
            assert result is None, (
                f"Expected None (null embedding), got {type(result)}. "
                "A zero vector must not be stored in the wardrobe DB."
            )

    def test_get_embedding_returns_mock_vector_when_mock_enabled(self):
        import sys
        for key in list(sys.modules):
            if "app.services.embedder" in key:
                del sys.modules[key]
        with patch.dict("os.environ", {"CV_ALLOW_MOCK": "true"}):
            import importlib
            import app.services.embedder as emb
            importlib.reload(emb)
            assert not emb.is_loaded()
            img = Image.new("RGB", (64, 64), (200, 200, 200))
            result = emb.get_embedding(img)
            assert result is not None
            assert len(result) == 512


class TestHealthEndpointContract:
    """The /health response must accurately reflect mock status."""

    def test_health_fields_present(self):
        from app.models.schemas import HealthResponse
        r = HealthResponse(
            status="degraded",
            mock_allowed=False,
            models_loaded={"grounding_dino": False, "sam": False, "fashion_clip": False},
            detail="Models not loaded.",
        )
        assert r.status == "degraded"
        assert r.mock_allowed is False

    def test_mock_status_string(self):
        from app.models.schemas import HealthResponse
        r = HealthResponse(
            status="mock",
            mock_allowed=True,
            models_loaded={"grounding_dino": False, "sam": False, "fashion_clip": False},
            detail="Running with mock detections.",
        )
        assert r.status == "mock"
        assert r.mock_allowed is True

    def test_detection_only_status(self):
        """The detection_only status represents the current real state:
        Grounding-DINO loaded, FashionCLIP not yet — detection works, embeddings null."""
        from app.models.schemas import HealthResponse
        r = HealthResponse(
            status="detection_only",
            mock_allowed=False,
            models_loaded={"grounding_dino": True, "sam": False, "fashion_clip": False},
            detail="Real detection active. Embeddings null until FashionCLIP loads.",
        )
        assert r.status == "detection_only"
        assert r.models_loaded["grounding_dino"] is True
        assert r.models_loaded["fashion_clip"] is False
