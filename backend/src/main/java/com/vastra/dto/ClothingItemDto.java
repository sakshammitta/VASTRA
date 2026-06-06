package com.vastra.dto;

import com.vastra.entity.ClothingCategory;
import com.vastra.entity.ClothingItemEntity;
import com.vastra.entity.OwnershipStatus;
import java.math.BigDecimal;
import java.util.List;

public class ClothingItemDto {
    public record ClothingItemResponse(
        String id, String catalogId, String ownerId, String ownershipStatus,
        String imageUrl, String thumbnailUrl, String category, String subCategory,
        List<String> tags, List<String> colorPalette, String brand,
        String purchasePlatform, String purchaseUrl, BigDecimal priceUsd,
        int styleMatchPercent, String addedAt,
        // Provenance of imageUrl: CROP (real crop), WEB_PRODUCT (confirmed match),
        // or AI_RENDER (generated). Lets the app badge non-real display images.
        String displayImageSource,
        // User-edit provenance: true once the user corrected this item's identity.
        // The app can badge "edited" and clients know the type is user-authoritative.
        boolean userEdited,
        String userEditedAt
    ) {
        public static ClothingItemResponse from(ClothingItemEntity item, String imageUrl, String thumbnailUrl) {
            return new ClothingItemResponse(
                item.getId().toString(), item.getCatalogId(),
                item.getOwner().getId().toString(), item.getOwnershipStatus().name(),
                imageUrl, thumbnailUrl,
                item.getCategory().name(), item.getSubCategory(),
                item.getTags() != null ? List.copyOf(item.getTags()) : List.of(),
                item.getColorPalette() != null ? List.copyOf(item.getColorPalette()) : List.of(),
                item.getBrand(),
                item.getPurchasePlatform(), item.getPurchaseUrl(), item.getPriceUsd(),
                item.getStyleMatchPercent(),
                item.getAddedAt() != null ? item.getAddedAt().toString() : null,
                item.getDisplayImageSource() != null ? item.getDisplayImageSource().name() : "PENDING",
                item.isUserEdited(),
                item.getUserEditedAt() != null ? item.getUserEditedAt().toString() : null
            );
        }
    }

    /**
     * Sent to PUT /api/wardrobe/items/{id} to correct a saved item's identity.
     * All fields are optional (partial update) — only non-null fields are applied.
     * Applying any field marks the item user_edited=true so the correction becomes
     * canonical and is never overwritten by AI/web-match/refresh.
     */
    public record UpdateItemRequest(
        String category,
        String subCategory,
        String brand,
        String ownershipStatus,
        BigDecimal priceUsd,
        List<String> tags
    ) {}

    public record CreateItemRequest(
        String catalogId, String ownershipStatus, String category,
        String subCategory, List<String> tags, String brand, BigDecimal priceUsd
    ) {}

    /**
     * Sent to POST /api/wardrobe/scan/{jobId}/confirm to save one detected item.
     * itemIndex selects which entry in detectedItems to persist.
     * All other fields are optional overrides; omitting category uses the CV-detected label.
     *
     * Display image selection (both optional — omit to keep CROP default):
     *   webMatchImageUrl  : external URL of the confirmed web product thumbnail to download + store
     *   aiRenderKey       : R2 key of a pre-generated AI render (from /ai-render endpoint)
     *   webMatchSourceUrl : attribution web page URL stored alongside the download
     */
    public record ConfirmScanItemRequest(
        int itemIndex,
        String ownershipStatus,
        String category,
        String subCategory,
        List<String> tags,
        String brand,
        BigDecimal priceUsd,
        String webMatchImageUrl,
        String aiRenderKey,
        String webMatchSourceUrl
    ) {}

    /**
     * Optional corrected-identity overrides sent to the web-match and ai-render
     * endpoints. Lets matching/rendering use the user's CONFIRMED attributes
     * (e.g. "joggers") instead of the CV guess (e.g. "trousers"). All nullable —
     * omitted fields fall back to the CV prediction stored in the scan job.
     */
    public record EnhanceImageRequest(
        String category,
        String subCategory,
        String brand
    ) {}

    /**
     * Sent to POST /api/wardrobe/items/{id}/display-image to set the confirmed
     * clean display image on an ALREADY-SAVED wardrobe item (e.g. enhancing a
     * PENDING item later). Exactly one of the two image sources should be set:
     *   webMatchImageUrl  : external URL of the confirmed web product thumbnail
     *                       to download + store (with webMatchSourceUrl attribution)
     *   aiRenderKey       : R2 key of an approved AI render (from /ai-render)
     * Sending neither leaves the item unchanged.
     */
    public record SetDisplayImageRequest(
        String webMatchImageUrl,
        String aiRenderKey,
        String webMatchSourceUrl
    ) {}

    /** One visual product-match candidate returned by the web-match endpoint. */
    public record WebMatchCandidate(
        String title,
        String imageUrl,
        String sourceUrl,
        String siteName,
        /** Non-null when the candidate's detected color differs from the item's palette. */
        String colorWarning
    ) {}

    /**
     * Response from POST /scan/{jobId}/items/{idx}/web-match.
     * available=false  — SerpAPI key not configured.
     * candidates empty — results were found but ALL scored below the wardrobe
     *                    quality threshold. The client shows "No clean product
     *                    match found" and offers AI render instead.
     */
    public record WebMatchResponse(
        List<WebMatchCandidate> candidates,
        boolean available
    ) {}

    /**
     * Response from POST /scan/{jobId}/items/{idx}/ai-render.
     * available=false means OpenAI is not configured in this environment.
     * renderUrl/renderKey are null on failure even when available=true.
     * failureReason is a human-readable description of why generation failed,
     * null on success.
     */
    public record AiRenderResponse(
        String renderUrl,
        String renderKey,
        boolean available,
        String failureReason
    ) {}
}
