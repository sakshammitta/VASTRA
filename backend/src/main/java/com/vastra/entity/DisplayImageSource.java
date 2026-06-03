package com.vastra.entity;

/**
 * Provenance of the wardrobe DISPLAY image (the picture shown in the app),
 * which is intentionally separate from the TRUTH image (the real extracted crop
 * region stored in r2ImageKey, used as evidence/reference for detected
 * attributes and as input for web matching / AI rendering).
 *
 * <p>Only two sources may ever become the final image shown in the wardrobe:
 * <ol>
 *   <li>{@link #WEB_PRODUCT} — a web/product match the user explicitly confirmed
 *       ("Is this your item? → Yes, use this")</li>
 *   <li>{@link #AI_RENDER} — a clean standalone render the user explicitly
 *       approved ("Use this clean image")</li>
 * </ol>
 *
 * <p>{@link #PENDING} means no clean display image has been confirmed yet. In
 * this state the app shows a placeholder ("Clean wardrobe image not generated
 * yet"), NOT the raw crop. The messy selfie/source crop is reference-only and
 * is never shown as the wardrobe item.
 *
 * <p>{@link #CROP} is retained ONLY for backward compatibility with rows created
 * before this policy. New items are never written as CROP.
 *
 * <p>A display image is NEVER chosen silently: WEB_PRODUCT and AI_RENDER are
 * only set after explicit user confirmation in the review flow. The display
 * image never changes the canonical attributes (category, subCategory, colors).
 */
public enum DisplayImageSource {
    WEB_PRODUCT,
    AI_RENDER,
    PENDING,
    CROP
}
