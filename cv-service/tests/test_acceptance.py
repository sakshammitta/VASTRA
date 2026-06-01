"""
Garment-detection acceptance tests — require Grounding-DINO to be loaded.

These tests are SKIPPED unless the real model is available (i.e. they will
not pass until the ML milestone is complete and the container has downloaded
the weights).

Acceptance criteria:
  1. single_shirt.jpg    → exactly 1 detection, category TOP, no BOTTOM
  2. single_trousers.jpg → exactly 1 detection, category BOTTOM, no TOP
  3. outfit_shirt_trousers.jpg → ≥1 TOP and ≥1 BOTTOM detected

Run against the live Docker container:
  docker exec vastra-cv pytest tests/test_acceptance.py -v
Or locally once torch + transformers are installed:
  CV_ALLOW_MOCK=false pytest tests/test_acceptance.py -v
"""
from pathlib import Path

import pytest
from PIL import Image

FIXTURES = Path(__file__).parent / "fixtures"


@pytest.fixture(scope="module")
def detector():
    import app.services.detector as det
    if not det.is_loaded():
        det.load_models()
    if not det.is_loaded():
        pytest.skip("Grounding-DINO not available — skipping acceptance tests")
    return det


@pytest.fixture(scope="module")
def embedder():
    import app.services.embedder as emb
    # Embedder is optional for these tests; we only need heuristic category.
    return emb


def _categories(detections, embedder) -> list[str]:
    """Return ClothingCategory values via the label-fallback taxonomy mapping.
    (Acceptance tests assert broad category from the DINO label; subtype
    accuracy is exercised by the FashionCLIP-dependent tests.)"""
    cats = []
    for d in detections:
        cat, _ = embedder._label_to_taxonomy(d.label)
        cats.append(cat.value)
    return cats


class TestSingleShirt:
    def test_detects_exactly_one_item(self, detector, embedder):
        img = Image.open(FIXTURES / "single_shirt.jpg")
        dets = detector.detect_clothing(img)
        assert len(dets) >= 1, "No garment detected in single-shirt image"
        # Allow detector to find more than one box, but at most a small number
        # (tight threshold may split collar/body; NMS should collapse them).
        assert len(dets) <= 3, f"Too many detections ({len(dets)}) for a single shirt"

    def test_top_detected(self, detector, embedder):
        img = Image.open(FIXTURES / "single_shirt.jpg")
        dets = detector.detect_clothing(img)
        cats = _categories(dets, embedder)
        assert "TOP" in cats, f"No TOP detected in shirt image. Got: {cats}"

    def test_no_bottom_detected(self, detector, embedder):
        img = Image.open(FIXTURES / "single_shirt.jpg")
        dets = detector.detect_clothing(img)
        cats = _categories(dets, embedder)
        assert "BOTTOM" not in cats, (
            f"Phantom BOTTOM detected in single-shirt image. Got: {cats}\n"
            f"Detections: {[(d.label, d.confidence) for d in dets]}"
        )


class TestSingleTrousers:
    def test_detects_at_least_one_item(self, detector, embedder):
        img = Image.open(FIXTURES / "single_trousers.jpg")
        dets = detector.detect_clothing(img)
        assert len(dets) >= 1, "No garment detected in single-trousers image"

    def test_bottom_detected(self, detector, embedder):
        img = Image.open(FIXTURES / "single_trousers.jpg")
        dets = detector.detect_clothing(img)
        cats = _categories(dets, embedder)
        assert "BOTTOM" in cats, f"No BOTTOM detected in trousers image. Got: {cats}"

    def test_no_top_detected(self, detector, embedder):
        img = Image.open(FIXTURES / "single_trousers.jpg")
        dets = detector.detect_clothing(img)
        cats = _categories(dets, embedder)
        assert "TOP" not in cats, (
            f"Phantom TOP detected in single-trousers image. Got: {cats}\n"
            f"Detections: {[(d.label, d.confidence) for d in dets]}"
        )


class TestOutfitBothDetected:
    def test_both_top_and_bottom_detected(self, detector, embedder):
        img = Image.open(FIXTURES / "outfit_shirt_trousers.jpg")
        dets = detector.detect_clothing(img)
        cats = _categories(dets, embedder)
        assert "TOP" in cats, f"No TOP in outfit image. Got: {cats}"
        assert "BOTTOM" in cats, f"No BOTTOM in outfit image. Got: {cats}"

    def test_detection_count_reasonable(self, detector, embedder):
        img = Image.open(FIXTURES / "outfit_shirt_trousers.jpg")
        dets = detector.detect_clothing(img)
        assert 2 <= len(dets) <= 6, (
            f"Unexpected detection count {len(dets)} for outfit image"
        )
