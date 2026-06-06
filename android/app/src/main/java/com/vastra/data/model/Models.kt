package com.vastra.data.model

import com.google.gson.annotations.SerializedName

data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
    val bio: String?,
    val styleProfile: StyleProfile,
    val followerCount: Int = 0,
    val followingCount: Int = 0,
    val postCount: Int = 0,
    val itemCount: Int = 0,
    val createdAt: String = ""
)

data class StyleProfile(
    val embedding: List<Float> = emptyList(),
    val preferredCategories: List<String> = emptyList(),
    val priceRange: PriceRange = PriceRange(0, 500),
    val swipeHistory: List<SwipeEvent> = emptyList()
)

data class PriceRange(val minUsd: Int, val maxUsd: Int)

data class SwipeEvent(
    val itemId: String,
    val direction: SwipeDirection,
    val timestamp: Long = System.currentTimeMillis()
)

enum class SwipeDirection { LIKE, DISLIKE, SUPER_LIKE }

data class ClothingItem(
    val id: String,
    val catalogId: String?,
    val ownerId: String,
    val ownershipStatus: OwnershipStatus,
    // Nullable: PENDING items have no confirmed clean display image yet, so the
    // backend legitimately returns null here. Deserializing this as non-null
    // crashes the wardrobe card with a NullPointerException.
    val imageUrl: String? = null,
    val thumbnailUrl: String? = null,
    val category: ClothingCategory,
    val subCategory: String = "",
    val tags: List<String> = emptyList(),
    val colorPalette: List<String> = emptyList(),
    val brand: String? = null,
    val purchasePlatform: String? = null,
    val purchaseUrl: String? = null,
    val priceUsd: Float? = null,
    val styleMatchPercent: Int = 0,
    val addedAt: String = "",
    // PENDING | CROP | WEB_PRODUCT | AI_RENDER — nullable so an absent/unknown
    // source from the backend never crashes deserialization or the card.
    val displayImageSource: String? = null
)

enum class OwnershipStatus { OWNED, ASPIRATIONAL }

enum class ClothingCategory(val label: String) {
    TOP("Top"), BOTTOM("Bottom"), OUTERWEAR("Outerwear"),
    FOOTWEAR("Footwear"), ACCESSORY("Accessory"), BAG("Bag"),
    DRESS("Dress"), SUIT("Suit"), TRADITIONAL("Traditional"), OTHER("Other")
}

data class Post(
    val id: String,
    val author: User,
    val imageUrl: String,
    val caption: String?,
    val shoppableTags: List<ShoppableTag> = emptyList(),
    val taggedItemIds: List<String> = emptyList(),
    val styleMetadata: StyleMetadata = StyleMetadata(),
    val likeCount: Int = 0,
    val commentCount: Int = 0,
    val saveCount: Int = 0,
    val isLikedByMe: Boolean = false,
    val isSavedByMe: Boolean = false,
    val visibility: PostVisibility = PostVisibility.PUBLIC,
    val createdAt: String = ""
)

data class ShoppableTag(
    val itemId: String,
    val xNorm: Float,
    val yNorm: Float,
    val label: String
)

data class StyleMetadata(
    val dominantCategories: List<String> = emptyList(),
    val dominantColors: List<String> = emptyList(),
    val styleLabels: List<String> = emptyList()
)

enum class PostVisibility { PUBLIC, FRIENDS_ONLY, PRIVATE }

/** Raw item returned by GET /api/wardrobe/scan/{jobId} — not yet saved to wardrobe. */
data class DetectedScanItem(
    @com.google.gson.annotations.SerializedName("crop_key")   val cropKey: String?,
    @com.google.gson.annotations.SerializedName("cropUrl")    val cropUrl: String?,
    @com.google.gson.annotations.SerializedName("category")   val category: ClothingCategory = ClothingCategory.OTHER,
    @com.google.gson.annotations.SerializedName("sub_category") val subCategory: String = "",
    @com.google.gson.annotations.SerializedName("subtype_confidence") val subtypeConfidence: Float = 0f,
    @com.google.gson.annotations.SerializedName("color_palette") val colorPalette: List<String> = emptyList()
)

data class ScanJob(
    val jobId: String,
    val status: ScanStatus,
    val detectedItems: List<DetectedScanItem> = emptyList(),
    val errorMessage: String? = null
)

enum class ScanStatus { QUEUED, PROCESSING, COMPLETE, FAILED }

/** One visual product-match candidate from SerpAPI Google Lens. */
data class WebMatchCandidate(
    val title: String,
    val imageUrl: String,
    val sourceUrl: String,
    val siteName: String?,
    /** Non-null when the candidate's title mentions a different color than detected. */
    val colorWarning: String? = null
)

/** Response from POST /scan/{jobId}/items/{idx}/web-match. */
data class WebMatchResponse(
    val candidates: List<WebMatchCandidate>,
    val available: Boolean
)

/** Response from POST /scan/{jobId}/items/{idx}/ai-render. */
data class AiRenderResponse(
    val renderUrl: String?,
    val renderKey: String?,
    val available: Boolean,
    /** Human-readable reason for failure, null on success. */
    val failureReason: String? = null
)

data class Comment(
    val id: String,
    val author: User,
    val text: String,
    val createdAt: String
)

data class RecreateStyleResponse(
    val ownedMatches: List<ClothingItem>,
    val gapItems: List<ClothingItem>,
    val matchScore: Float
)

data class StyleRecommendation(
    val item: ClothingItem,
    val reason: String,
    val matchScore: Float,
    val priceOptions: List<PriceOption>
)

data class PriceOption(
    val label: String,
    val priceUsd: Float,
    val purchaseUrl: String,
    val brand: String
)
