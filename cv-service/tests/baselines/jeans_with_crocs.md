# Baseline failure case: jeans + visible Crocs (multi-item)

Status: KNOWN FAILURE (recorded 2026-06-02). Do not delete — used to compare
any future detector prompt / taxonomy / NMS / model change.

## Input
A photo intended to capture jeans, with the user's Crocs (clog-style footwear)
also visible in the frame. Realistic worn / multi-item scene.

## Observed review-screen result (pre-fix baseline)
| Detection | Category  | Type  | Verdict |
|-----------|-----------|-------|---------|
| jeans     | Bottom    | jeans | CORRECT |
| crocs     | Dress     | dress | WRONG — footwear classified as a garment |
| (extra)   | Outerwear | coat  | WRONG — duplicate/fragmented crop of same footwear region |

User did not save (extra detections wrong).

## Root causes confirmed in code
1. `detector.py` CLOTHING_PROMPT lacks footwear terms: `sandals`, `clogs`,
   `slides`, `crocs` (only `shoes . boots . sneakers`).
2. `embedder.py` _SUBTYPE_TO_CATEGORY lacks those footwear classes, AND
   classify_subtype() forces an argmax over the full taxonomy with no
   confidence floor → unusual footwear gets the nearest garment label.
3. `detector._deduplicate()` only merges IoU >= 0.5 and only replaces generic
   labels → two specific boxes (dress + coat) over one footwear region survive.

## What "fixed" looks like (acceptance for this case)
- jeans still -> Bottom / jeans
- crocs -> Footwear (sandals/clogs acceptable subtype), single detection
- no spurious Dress or Outerwear/coat detection

## How to reproduce
Re-run the same source photo through POST /scan/inspect (direct upload, no
persistence) and compare the items list against the table above.
