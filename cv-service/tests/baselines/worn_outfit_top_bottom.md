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

## Relationship to other baselines
- tests/baselines/jeans_with_crocs.md: secondary failure (footwear taxonomy)
  Do NOT address crocs/footwear until this baseline passes.
