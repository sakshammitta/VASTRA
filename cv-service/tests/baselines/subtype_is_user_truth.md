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

## Not yet implemented (tracked)
1. AI provenance: the original predicted subtype + confidence are NOT persisted
   (they live only in the 24h Redis scan job). Optional ("may be kept").
   Would require nullable columns ai_predicted_subcategory, ai_subtype_confidence.
2. Recommendation engine currently ignores subCategory entirely — it uses the
   FashionCLIP embedding + a Math.random() placeholder score
   (RecommendationService.java:77). Type/formality/occasion-aware matching is
   future work. When built it must read the confirmed subCategory + use
   com.vastra.domain.SubtypeVocabulary formality.
3. Review UI Type field is free-text. SubtypeVocabulary.suggestionsFor(category)
   is prepared to back a suggestion list while keeping free-text correction.

## Normalized bottoms vocabulary (prepared)
com.vastra.domain.SubtypeVocabulary.BOTTOMS:
  jeans(3) trousers(4) chinos(3) joggers(1) sweatpants(1) cargo pants(2)
  shorts(2) skirt(3) leggings(1)   — numbers are 1-5 formality.

## Acceptance for the styling example
Olive joggers (formality 1) must NOT be recommended for a dinner/smart-casual
look that olive tailored trousers (formality 4) would suit. Enforce via a
formality-distance gate once the recommender is built.
