# Requirement: user-edited subtype is the saved truth (joggers vs trousers)

Status: VERIFIED for persistence (2026-06-02). Recommendation usage is future work.

## Requirement
The AI suggests a subtype (e.g. `trousers`); the user may correct it (e.g.
`joggers`) in the review UI before saving. The corrected value must be the
stored truth and the value future recommendations use. trousers and joggers
share category BOTTOM but differ in formality, so color-only matching is wrong.

## Verified behaviour (code evidence)
- Android WardrobeViewModel.confirmSelectedItems sends the edited subCategory
  (editedSubcategories[index], seeded from the CV guess, overwritten on edit).
- Backend WardrobeService.confirmScanItem line 128:
      item.setSubCategory(req.subCategory() != null ? req.subCategory() : cvSubCategory);
  The CV prediction is only a FALLBACK; the user edit always wins.
- ClothingItemEntity has a single `subCategory` column → no second path can
  overwrite the confirmed value with the raw AI label.

## Implemented (2026-06-02)
1. AI provenance columns (migration V4__ai_provenance.sql), nullable, write-once
   at confirm time in WardrobeService.confirmScanItem:
     - ai_predicted_category, ai_predicted_sub_category, ai_subtype_confidence,
       ai_model_source ("grounding-dino-base+fashion-clip")
   These NEVER override category/subCategory — those stay the confirmed truth.
   Provenance is for debugging/analytics/model improvement only. Query the DB
   to measure AI-correct vs user-corrected during outfit testing.
2. Review-UI Type field: now an editable field with normalized subtype
   SUGGESTIONS per category (Android SubtypeVocabulary mirrors the backend one),
   still free-text so a custom value can be typed. Whatever is confirmed is saved.
3. Placeholder match scoring is hidden/marked (not presented as real):
   - SwipeScreen "% match" badge removed (was driven by styleMatchPercent placeholder)
   - FeedScreen RecreateStyleDialog now shows "Preview · style matching is not
     live yet" instead of a fabricated match % (backed by Math.random()).

## Not yet implemented (tracked)
1. Recommendation engine still ignores subCategory — uses the FashionCLIP
   embedding + a Math.random() placeholder score (RecommendationService.java:77).
   Type/formality/occasion-aware matching is future work. When built it must read
   the confirmed subCategory and use com.vastra.domain.SubtypeVocabulary formality.
   Until then NO style-match output is shown as a real number anywhere.
2. AI provenance is stored but not yet exposed via the API/response DTO — read
   it from Postgres directly for now.

## Normalized bottoms vocabulary (prepared)
com.vastra.domain.SubtypeVocabulary.BOTTOMS:
  jeans(3) trousers(4) chinos(3) joggers(1) sweatpants(1) cargo pants(2)
  shorts(2) skirt(3) leggings(1)   — numbers are 1-5 formality.

## Acceptance for the styling example
Olive joggers (formality 1) must NOT be recommended for a dinner/smart-casual
look that olive tailored trousers (formality 4) would suit. Enforce via a
formality-distance gate once the recommender is built.
