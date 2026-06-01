"""
Real-image garment-detection acceptance tests.

Unlike the synthetic fixtures, these run against ACTUAL photos you place in
tests/fixtures/real/ (see that directory's README for the naming convention).

Requires Grounding-DINO to be loaded; otherwise every test is skipped.

Acceptance criteria:
  shirt*/top*    → exactly one TOP, zero BOTTOM
  trousers*/...  → one BOTTOM, zero TOP
  outfit*        → both TOP and BOTTOM present

Run inside the running container:
  docker exec vastra-cv pytest tests/test_real_images.py -v
"""
from pathlib import Path

import pytest
from PIL import Image

REAL = Path(__file__).parent / "fixtures" / "real"
EXTS = {".jpg", ".jpeg", ".png", ".webp"}

TOP_PREFIXES = ("shirt", "top", "tshirt", "t-shirt")
BOTTOM_PREFIXES = ("trousers", "pants", "jeans", "bottom")
OUTFIT_PREFIXES = ("outfit",)


def _images_with_prefix(prefixes: tuple[str, ...]) -> list[Path]:
    if not REAL.exists():
        return []
    out = []
    for p in sorted(REAL.iterdir()):
        if p.suffix.lower() in EXTS and p.stem.lower().startswith(prefixes):
            out.append(p)
    return out


@pytest.fixture(scope="module")
def detector():
    import app.services.detector as det
    if not det.is_loaded():
        det.load_models()
    if not det.is_loaded():
        pytest.skip("Grounding-DINO not available — skipping real-image tests")
    return det


@pytest.fixture(scope="module")
def heuristic():
    import app.services.embedder as emb
    return emb._heuristic_category


def _categories(detections, heuristic) -> list[str]:
    return [heuristic(d.label)[0].value for d in detections]


def _detail(detections) -> str:
    return ", ".join(f"{d.label}({d.confidence:.2f})" for d in detections) or "<none>"


@pytest.mark.parametrize(
    "img_path",
    _images_with_prefix(TOP_PREFIXES) or [pytest.param(None, marks=pytest.mark.skip(reason="no shirt/top image provided"))],
    ids=lambda p: p.name if p else "none",
)
def test_top_only(detector, heuristic, img_path):
    dets = detector.detect_clothing(Image.open(img_path).convert("RGB"))
    cats = _categories(dets, heuristic)
    assert "TOP" in cats, f"{img_path.name}: expected TOP, got [{_detail(dets)}]"
    assert "BOTTOM" not in cats, f"{img_path.name}: phantom BOTTOM, got [{_detail(dets)}]"


@pytest.mark.parametrize(
    "img_path",
    _images_with_prefix(BOTTOM_PREFIXES) or [pytest.param(None, marks=pytest.mark.skip(reason="no trousers/pants image provided"))],
    ids=lambda p: p.name if p else "none",
)
def test_bottom_only(detector, heuristic, img_path):
    dets = detector.detect_clothing(Image.open(img_path).convert("RGB"))
    cats = _categories(dets, heuristic)
    assert "BOTTOM" in cats, f"{img_path.name}: expected BOTTOM, got [{_detail(dets)}]"
    assert "TOP" not in cats, f"{img_path.name}: phantom TOP, got [{_detail(dets)}]"


@pytest.mark.parametrize(
    "img_path",
    _images_with_prefix(OUTFIT_PREFIXES) or [pytest.param(None, marks=pytest.mark.skip(reason="no outfit image provided"))],
    ids=lambda p: p.name if p else "none",
)
def test_outfit_both(detector, heuristic, img_path):
    dets = detector.detect_clothing(Image.open(img_path).convert("RGB"))
    cats = _categories(dets, heuristic)
    assert "TOP" in cats, f"{img_path.name}: expected TOP, got [{_detail(dets)}]"
    assert "BOTTOM" in cats, f"{img_path.name}: expected BOTTOM, got [{_detail(dets)}]"
