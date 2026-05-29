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
        int styleMatchPercent, String addedAt
    ) {
        public static ClothingItemResponse from(ClothingItemEntity item, String imageUrl, String thumbnailUrl) {
            return new ClothingItemResponse(
                item.getId().toString(), item.getCatalogId(),
                item.getOwner().getId().toString(), item.getOwnershipStatus().name(),
                imageUrl, thumbnailUrl,
                item.getCategory().name(), item.getSubCategory(),
                item.getTags(), item.getColorPalette(), item.getBrand(),
                item.getPurchasePlatform(), item.getPurchaseUrl(), item.getPriceUsd(),
                item.getStyleMatchPercent(),
                item.getAddedAt() != null ? item.getAddedAt().toString() : null
            );
        }
    }

    public record CreateItemRequest(
        String catalogId, String ownershipStatus, String category,
        String subCategory, List<String> tags, String brand, BigDecimal priceUsd
    ) {}
}
