# Hybrid wardrobe-image strategy (truth layer vs display layer)

Status: FOUNDATION implemented 2026-06-02. Web-match + AI-render are DESIGNED,
not built (they need external-provider decisions — see "Blocked").

## Principle: separate truth from display
TRUTH LAYER (canonical, never overwritten by display choices)
  - r2_image_key            : the real extracted crop region (evidence)
  - category / sub_category : detected then USER-CONFIRMED type (canonical)
  - color_palette           : detected colors
  - ai_predicted_* / ai_subtype_confidence / ai_model_source : provenance only
  - (future) material, brand guess
DISPLAY LAYER (what the app renders; may be cleaner than the crop)
  - display_image_key       : NULL until a cleaner image is confirmed
  - display_image_source    : CROP | WEB_PRODUCT | AI_RENDER  (default CROP)

The displayed image resolves via ClothingItemEntity.getEffectiveImageKey():
display_image_key if set, else r2_image_key. Changing the display image NEVER
changes the canonical attributes used by recommendations.

## Display-source priority (highest first)
1. WEB_PRODUCT — strong web/product-catalog match the user CONFIRMED
2. AI_RENDER   — clean standalone render generated from the detected garment
                 when no strong web match exists
3. CROP        — the real extracted crop (always available; safe fallback)

## Hard rules
- NEVER silently replace the real item with a web/AI image. WEB_PRODUCT and
  AI_RENDER are only set after explicit "Is this your item?" confirmation in
  the review flow.
- Confirmed category/subtype is the canonical style input. Editing trousers ->
  joggers means recommendations treat it as joggers. (Already enforced:
  WardrobeService.confirmScanItem uses req.subCategory(); single subCategory
  column; see subtype_is_user_truth.md.)
- Until the confirmation feature ships, every item stays display_image_source =
  CROP, so behaviour is unchanged and honest.

## Implemented now (additive, no behaviour change)
- DisplayImageSource enum
- V5__display_image_layer.sql (display_image_key, display_image_source DEFAULT CROP)
- ClothingItemEntity fields + getEffectiveImageKey()
- WardrobeService list path renders getEffectiveImageKey()
- ClothingItemResponse exposes displayImageSource so the app can badge non-CROP

## Designed, NOT built — proposed candidate flow
At review time, for each confirmed garment:
  1. Build a query from confirmed attrs (e.g. "white Nike t-shirt center swoosh").
  2. Web image/product search -> top candidates with thumbnails + source URLs.
  3. Show candidates in the review sheet: "Is this your item?" [use this] / [no].
  4. If confirmed -> store image as display_image_key, source=WEB_PRODUCT.
  5. If none confirmed and user opts in -> AI render -> display_image_key,
     source=AI_RENDER.
  6. Else stay CROP.
New storage likely needed later: a candidate table or a transient list on the
scan job; a confirmed web source URL/attribution field.

## Blocked on decisions (cannot implement without these)
1. Web product/image search PROVIDER (API key, cost, attribution/licensing):
   options incl. Google Vision Product Search, Bing Image Search, SerpAPI,
   specific retailer APIs. Legal: storing third-party product images.
2. AI image-generation PROVIDER + model + cost ceiling per render, and whether
   renders are generated on-demand at confirm or queued.
3. Where the brand/material guess comes from (FashionCLIP zero-shot vs a
   dedicated classifier) — needed to build good web queries.

## Acceptance (when built)
- Upload worn white Nike tee -> review offers likely product images ->
  user confirms -> wardrobe shows the clean product image, badged WEB_PRODUCT,
  while category/subtype/colors remain the confirmed truth.
- No confirmation -> item shows the real crop (CROP), never a random web item.
