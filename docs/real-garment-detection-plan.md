# Real Garment Detection — Technical Plan

Status: planning. Goal of the first milestone: **prove that a single shirt
image produces exactly one `shirt`/`TOP` detection and NOT a fabricated
`pants`/`BOTTOM` item.**

---

## 1. Why the app currently invents pants

The CV pipeline is fully written but every stage **silently falls back to a
mock** because the ML libraries are commented out of `requirements.txt` and
no model weights ship in the image.

The specific culprit is `cv-service/app/services/detector.py:90-104`:

```python
def _mock_detections(image):
    return [
        Detection(label="shirt", confidence=0.92, bbox=...),  # top half
        Detection(label="pants", confidence=0.88, bbox=...),  # bottom half
    ]
```

`detect_clothing()` returns this whenever `_models_loaded` is `False`
(`detector.py:44-45`). Loading is attempted in `load_models()` but the
`transformers` / `torch` imports fail (commented out in
`cv-service/requirements.txt:13-14`), so the service starts, logs a warning,
and serves the hardcoded shirt+pants for **every** image.

The same pattern affects the downstream stages, which is why even the
"shirt" half is not real:
- `embedder.get_embedding()` → `_mock_embedding()` returns a fixed random
  512-vector (`embedder.py:148-152`), so similarity search is meaningless.
- `embedder.classify_category()` → `_heuristic_category()` keyword match on
  the (mock) label (`embedder.py:155-160`).
- `segmenter.segment_crop()` → `_crop_fallback()` plain bbox crop
  (`segmenter.py:86-93`) — acceptable as an interim, no model needed.

**Conclusion:** the architecture is correct. We do not need to rewrite the
pipeline — we need to make the real detector actually load, and make the mock
honest/inert so a shirt photo can never yield phantom pants.

---

## 2. Architecture recap (already in place)

```
Android Scan → POST /api/wardrobe/scan (multipart)
  → backend uploads image to R2, creates Redis job (QUEUED)
  → ScanJobService (async):
        POST cv-service /scan   → detections [{label, confidence, bbox}]
        POST cv-service /embed  → items [{detection, embedding(512),
                                          color_palette, category,
                                          sub_category, crop_key}]
        store items on Redis job (COMPLETE)
  → Android polls GET /scan/{jobId}; on COMPLETE shows selection sheet
  → user confirms → POST /scan/{jobId}/confirm {itemIndex}
        → persist ClothingItem (pgvector embedding) in Postgres
```

Models and their fallbacks:

| Stage | Real model | File | Fallback (current) |
|-------|-----------|------|--------------------|
| Detect | Grounding-DINO `IDEA-Research/grounding-dino-base` | detector.py | hardcoded shirt+pants ⚠️ |
| Segment | SAM `vit_b` | segmenter.py | bbox crop (ok) |
| Embed | FashionCLIP 512-d | embedder.py | fixed random vector ⚠️ |
| Classify | FashionCLIP text-image sim | embedder.py | label keyword map |
| Color | K-means LAB (no model) | embedder.py | average color |

---

## 3. Milestone 1 — Real detection (shirt-only proof)

The minimum to kill the phantom pants is to enable **Grounding-DINO**. SAM
can stay on the crop fallback; FashionCLIP is Milestone 2.

### 3.1 Make the mock honest (do first, low risk)
So we never again ship fabricated multi-item output unnoticed:
- Add `CV_ALLOW_MOCK` env flag (default `false` in non-dev).
- When models are not loaded and mock is disabled, `/scan` should return an
  **empty detection list** (or HTTP 503), not shirt+pants. An empty list
  surfaces in the app as "no items detected" — honest, not misleading.
- Keep the mock only for offline UI development, behind the flag.
- Surface real model status: the Android scanning UI / backend health check
  should read `GET /health` `models_loaded` and treat a mock service as
  degraded.

### 3.2 Enable Grounding-DINO
- Uncomment in `cv-service/requirements.txt`:
  ```
  torch==2.3.1            # CPU build is fine for a correctness proof
  torchvision==0.18.1
  transformers==4.42.3
  ```
- Use CPU wheels to keep the image runnable without a GPU:
  `pip install torch==2.3.1 --index-url https://download.pytorch.org/whl/cpu`
  (add to Dockerfile as a separate step).
- First run downloads `grounding-dino-base` (~700 MB) from HuggingFace. Cache
  it into the image or a mounted volume (`HF_HOME`) so containers start fast
  and work offline after the first pull.
- Expect ~2–6 s/inference on CPU. The flow is already async (Redis job +
  polling), so latency is acceptable for the proof.

### 3.3 Tighten detection quality (prevent over-detection)
Grounding-DINO is zero-shot and can emit overlapping/low-quality boxes.
Add post-processing in `detect_clothing()`:
- Raise `box_threshold` (try 0.35–0.4) and keep `text_threshold` ~0.25.
- **De-duplicate / NMS** across labels by IoU so the same garment is not
  reported as both "clothing" and "shirt".
- Map raw phrase labels (e.g. "shirt", "clothing") to canonical garment
  terms; drop the generic "clothing" box when a specific one overlaps it.
- Optional sanity rule for the proof: collapse boxes with high IoU and keep
  the highest-confidence specific label.

### 3.4 Validation — the actual proof
Add a repeatable test (script + a couple of fixtures):
1. `tests/fixtures/single_shirt.jpg` (one shirt, plain background) and
   `tests/fixtures/full_outfit.jpg` (top + bottom).
2. `pytest` in cv-service:
   - shirt image → exactly one detection, category `TOP`, no `BOTTOM`.
   - outfit image → both `TOP` and `BOTTOM` present.
3. End-to-end check via backend: scan `single_shirt.jpg`, poll job, assert
   `detectedItems` length == 1 and category TOP.
4. Manual device pass: scan a shirt → selection sheet shows one item.

Acceptance: shirt photo yields one TOP item and zero phantom BOTTOM, with
mock disabled, on a clean container.

---

## 4. Milestone 2 — Real embeddings & category (FashionCLIP)
- Uncomment `fashion-clip==0.2.1`; load in `embedder.load_models()`.
- Replaces the fixed random vector with a real 512-d embedding (needed for
  any future similarity / recommendations) and real category classification.
- Remove `_mock_embedding`'s use in production via the same `CV_ALLOW_MOCK`
  gate; an unembeddable item should fail the job, not store a random vector
  in pgvector.
- Validate: two photos of the same shirt have high cosine similarity; a shirt
  vs. shoes have low similarity.

## 5. Milestone 3 — Real segmentation (SAM, optional/quality)
- Add `segment-anything` + download `sam_vit_b_01ec64.pth`; set
  `SAM_CHECKPOINT`. Improves crop quality (transparent background) but the
  bbox crop fallback is acceptable until then.

---

## 6. Risks / decisions to confirm
- **Compute**: CPU Grounding-DINO is slow but works for the proof. Decide
  whether the dev/demo environment gets a GPU before Milestone 2 quality work.
- **Image size**: model download (~700 MB DINO, ~600 MB FashionCLIP, ~375 MB
  SAM). Use layer caching / a model volume; don't bloat every rebuild.
- **Network policy**: first model pull needs outbound HuggingFace access.
  Confirm the environment allows it or pre-bake weights.
- **Confidence threshold** is the main knob for "no phantom items"; tune on
  the fixtures.

## 7. Sequencing
1. (PR-1) Honest mock gate + `/health` surfacing + fixtures + failing test.
2. (PR-2) Enable Grounding-DINO (CPU), NMS/label cleanup → test goes green.
3. (PR-3) FashionCLIP embeddings + category.
4. (PR-4) SAM segmentation polish.

Milestone 1 (PR-1 + PR-2) delivers the requested proof.
