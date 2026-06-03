package com.vastra.data.remote

import com.vastra.data.model.*
import com.vastra.data.model.AiRenderResponse
import com.vastra.data.model.WebMatchResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.*

interface VastraApiService {

    @POST("api/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<AuthResponse>

    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @GET("api/feed")
    suspend fun getFeed(
        @Query("page") page: Int = 0,
        @Query("size") size: Int = 20
    ): Response<FeedPage>

    @POST("api/feed/posts")
    @Multipart
    suspend fun createPost(
        @Part image: MultipartBody.Part,
        @Part("caption") caption: okhttp3.RequestBody,
        @Part("visibility") visibility: okhttp3.RequestBody
    ): Response<Post>

    @POST("api/feed/posts/{id}/like")
    suspend fun likePost(@Path("id") postId: String): Response<Unit>

    @POST("api/feed/posts/{id}/save")
    suspend fun savePost(@Path("id") postId: String): Response<Unit>

    @GET("api/feed/posts/{id}/comments")
    suspend fun getComments(@Path("id") postId: String): Response<List<Comment>>

    @POST("api/feed/posts/{id}/comments")
    suspend fun addComment(
        @Path("id") postId: String,
        @Body request: AddCommentRequest
    ): Response<Comment>

    @POST("api/feed/posts/{id}/recreate")
    suspend fun recreateStyle(@Path("id") postId: String): Response<RecreateStyleResponse>

    @GET("api/wardrobe")
    suspend fun getWardrobe(
        @Query("category") category: String? = null,
        @Query("status") status: String? = null
    ): Response<List<ClothingItem>>

    @POST("api/wardrobe/scan")
    @Multipart
    suspend fun scanItem(@Part image: MultipartBody.Part): Response<ScanJobResponse>

    @GET("api/wardrobe/scan/{jobId}")
    suspend fun getScanStatus(@Path("jobId") jobId: String): Response<ScanJob>

    @POST("api/wardrobe/items")
    suspend fun createItem(@Body request: CreateItemRequest): Response<ClothingItem>

    @DELETE("api/wardrobe/items/{id}")
    suspend fun deleteItem(@Path("id") itemId: String): Response<Unit>

    @POST("api/wardrobe/scan/{jobId}/confirm")
    suspend fun confirmScanItem(
        @Path("jobId") jobId: String,
        @Body request: ConfirmScanItemRequest
    ): Response<ClothingItem>

    /** Runs SerpAPI Google Lens on the detected crop; returns visual-match candidates.
     *  Body carries the user-corrected identity so matching uses e.g. "joggers". */
    @POST("api/wardrobe/scan/{jobId}/items/{itemIndex}/web-match")
    suspend fun requestWebMatch(
        @Path("jobId") jobId: String,
        @Path("itemIndex") itemIndex: Int,
        @Body request: EnhanceImageRequest
    ): Response<WebMatchResponse>

    /** Image-edit render grounded in the crop; uploads to R2. Body carries
     *  the user-corrected identity so the render reflects e.g. "joggers". */
    @POST("api/wardrobe/scan/{jobId}/items/{itemIndex}/ai-render")
    suspend fun requestAiRender(
        @Path("jobId") jobId: String,
        @Path("itemIndex") itemIndex: Int,
        @Body request: EnhanceImageRequest
    ): Response<AiRenderResponse>

    @GET("api/recommendations/swipe")
    suspend fun getNextSwipeCard(): Response<ClothingItem>

    @POST("api/recommendations/swipe")
    suspend fun recordSwipe(@Body request: SwipeRequest): Response<Unit>

    @GET("api/recommendations/style")
    suspend fun getStyleRecommendations(): Response<List<StyleRecommendation>>

    @GET("api/profile/{username}")
    suspend fun getProfile(@Path("username") username: String): Response<User>

    @PUT("api/profile")
    suspend fun updateProfile(@Body request: UpdateProfileRequest): Response<User>

    @POST("api/profile/follow/{userId}")
    suspend fun followUser(@Path("userId") userId: String): Response<Unit>
}

data class RegisterRequest(val username: String, val displayName: String, val email: String, val password: String)
data class LoginRequest(val email: String, val password: String)
data class AuthResponse(val token: String, val user: User)
data class FeedPage(val content: List<Post>, val totalPages: Int, val currentPage: Int, val hasNext: Boolean)
data class ScanJobResponse(val jobId: String, val status: ScanStatus)
data class CreateItemRequest(
    val catalogId: String?,
    val ownershipStatus: OwnershipStatus,
    val category: ClothingCategory,
    val subCategory: String,
    val tags: List<String>,
    val brand: String?,
    val priceUsd: Float?
)
data class ConfirmScanItemRequest(
    val itemIndex: Int,
    val ownershipStatus: String = "OWNED",
    val category: String? = null,
    val subCategory: String? = null,
    val brand: String? = null,
    val tags: List<String>? = null,
    val priceUsd: Float? = null,
    // Display image selection — both optional; omit to default to CROP
    val webMatchImageUrl: String? = null,
    val aiRenderKey: String? = null,
    val webMatchSourceUrl: String? = null
)
data class EnhanceImageRequest(
    val category: String? = null,
    val subCategory: String? = null,
    val brand: String? = null
)
data class SwipeRequest(val itemId: String, val direction: SwipeDirection)
data class AddCommentRequest(val text: String)
data class UpdateProfileRequest(val displayName: String, val bio: String?, val priceMin: Int, val priceMax: Int)
