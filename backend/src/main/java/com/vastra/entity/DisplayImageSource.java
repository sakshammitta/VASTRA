package com.vastra.entity;

/**
 * Provenance of the wardrobe DISPLAY image (the picture shown in the app),
 * which is intentionally separate from the TRUTH image (the real extracted crop
 * region stored in r2ImageKey, used as evidence for detected attributes).
 *
 * <p>Display-source priority, highest first:
 * <ol>
 *   <li>{@link #WEB_PRODUCT} — a strong web/product-catalog match the user
 *       explicitly confirmed ("Is this your item?")</li>
 *   <li>{@link #AI_RENDER} — a clean standalone render generated from the
 *       detected garment when no strong web match exists</li>
 *   <li>{@link #CROP} — the real extracted crop from the source photo (always
 *       available; the safe fallback)</li>
 * </ol>
 *
 * <p>A display image is NEVER chosen silently: WEB_PRODUCT and AI_RENDER are
 * only set after the user confirms them in the review flow. Until that feature
 * ships, every item stays {@link #CROP}. The display image never changes the
 * canonical detected/confirmed attributes (category, subCategory, colors).
 */
public enum DisplayImageSource {
    WEB_PRODUCT,
    AI_RENDER,
    CROP
}
