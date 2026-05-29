package com.vastra.dto;

import com.vastra.entity.PostEntity;
import com.vastra.entity.ShoppableTagEntity;
import java.util.List;

public class PostDto {
    public record PostResponse(
        String id, UserDto.UserResponse author, String imageUrl, String caption,
        List<ShoppableTagResponse> shoppableTags, List<String> styleLabels,
        List<String> dominantColors, int likeCount, int commentCount, int saveCount,
        boolean isLikedByMe, boolean isSavedByMe, String visibility, String createdAt
    ) {}

    public record ShoppableTagResponse(String itemId, float xNorm, float yNorm, String label) {
        public static ShoppableTagResponse from(ShoppableTagEntity t) {
            return new ShoppableTagResponse(
                t.getItemId() != null ? t.getItemId().toString() : null,
                t.getXNorm(), t.getYNorm(), t.getLabel()
            );
        }
    }

    public record CommentResponse(String id, UserDto.UserResponse author, String text, String createdAt) {}

    public record AddCommentRequest(String text) {}

    public record FeedPage(List<PostResponse> content, int totalPages, int currentPage, boolean hasNext) {}

    public record RecreateStyleResponse(
        List<ClothingItemDto.ClothingItemResponse> ownedMatches,
        List<ClothingItemDto.ClothingItemResponse> gapItems,
        float matchScore
    ) {}

    public record StyleRecommendation(
        ClothingItemDto.ClothingItemResponse item, String reason, float matchScore,
        List<PriceOption> priceOptions
    ) {}

    public record PriceOption(String label, float priceUsd, String purchaseUrl, String brand) {}

    public record SwipeRequest(String itemId, String direction) {}
}
