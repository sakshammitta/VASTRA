# Baseline failure case: normal worn outfit photo — top + bottom

Status: KNOWN FAILURE (recorded 2026-06-02). PRIMARY product acceptance test.
Do not delete — this is the key test the pipeline must pass before the
outfit-first product direction is viable.

## Input
Normal full-body or torso photo of a person wearing a visible top and bottom
(e.g. t-shirt + jeans). Realistic worn/in-use scene. Not a flat lay, not a
mannequin, not a white-background product photo.

## Expected result
2 items detected:
  - TOP / t-shirt (or shirt/sweater/hoodie depending on garment worn)
  - BOTTOM / jeans (or trousers/shorts/skirt)

## Observed result (pre-fix baseline, production settings)
  0 items detected

User did not reach the review screen at all.

## What we know at time of recording
- Production thresholds: box=0.35, text=0.25
- Production prompt: "shirt . t-shirt . blouse . top . sweater . hoodie .
  pants . trousers . jeans . shorts . skirt . jacket . coat . blazer .
  dress . jumpsuit . shoes . boots . sneakers . bag . handbag . backpack .
  hat . scarf . belt"
- The same pipeline correctly detects isolated flat-lay / single-item photos
  (jeans on their own → Bottom/jeans was correct in an earlier test)
- Unknown at this point: whether DINO produced low-confidence boxes that were
  filtered by the 0.35 threshold, or genuinely produced zero boxes

## Diagnostic questions to answer (in order)
1. Run /scan/diagnose or tests/diagnose_outfit_detection.py on the photo
   → determine if detections exist below the 0.35 threshold
2. If yes: what is the highest confidence score, and for which label?
   → candidate fix: lower box_threshold
3. If no: which prompt variant produces detections?
   → candidate fix: update CLOTHING_PROMPT
4. Run the same photo after fix and confirm 2 items detected

## Acceptance bar
Same worn outfit photo returns:
  - ≥1 TOP detection (any of: shirt/t-shirt/blouse/sweater/hoodie)
  - ≥1 BOTTOM detection (any of: jeans/trousers/pants/shorts/skirt)
  - No spurious garment detections covering the face/background/skin
  - Isolated flat-lay jeans result from previous session still passes
    (no regression on single-item photos)

## Investigation progress (2026-06-02)

### Finding 1 — prompt, not threshold
/scan/diagnose on the gym-selfie outfit photo returned:
  - production@0.35 → 0 detections
  - production@0.25 → 0 detections
  - worn-outfit@0.25 → 5 detections (shirt 0.33, jeans 0.31, trousers 0.30,
    shirt/hoodie 0.27, hoodie/jacket 0.25)
Conclusion: the production prompt wording is the cause of "0 items", not the
threshold. The worn-outfit prompt ("<garment> worn by person") detects garments.

### Finding 2 — the 3 extra detections are background, not duplicates
/scan/diagnose/worn crop inspection on the same photo:
  - #0 shirt → FashionCLIP shirt, area=0.091  → REAL (white top)       KEEP
  - #2 trousers → FashionCLIP trousers, area=0.083 → REAL (olive bottom) KEEP
  - #1 → sneakers, area=0.003  → background fragment (gym/mirror)        REJECT
  - #3 → shirt,    area=0.005  → background fragment                     REJECT
  - #4 → hoodie,   area=0.002  → background fragment                     REJECT
The noise comes from other people / mirror reflections in the gym, NOT
duplicate boxes over the user's own clothes. So the fix is main-SUBJECT
association, not just NMS.

### Candidate fix under test — main-subject filter
POST /scan/diagnose/subject (experimental, read-only):
  1. worn-outfit garment prompt (box=0.25)
  2. person prompt → largest person box = primary subject
  3. keep garment iff area >= 0.02 AND >=55% contained in primary-person box
  4. FashionCLIP on kept garments only
  5. before/after annotated images
Tunables: _MIN_GARMENT_AREA=0.02, _MIN_CONTAINMENT=0.55 in scan.py.

## Acceptance bar
Same worn outfit photo returns:
  - ≥1 TOP detection (any of: shirt/t-shirt/blouse/sweater/hoodie)
  - ≥1 BOTTOM detection (any of: jeans/trousers/pants/shorts/skirt)
  - No spurious garment detections covering the face/background/skin
  - Specifically: the 3 background fragments above must be REJECTED
  - Isolated flat-lay jeans result from previous session still passes
    (no regression on single-item photos)

## Relationship to other baselines
- tests/baselines/jeans_with_crocs.md: secondary failure (footwear taxonomy)
  Do NOT address crocs/footwear until this baseline passes.
