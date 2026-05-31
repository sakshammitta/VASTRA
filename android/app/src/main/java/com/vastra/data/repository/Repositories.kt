package com.vastra.data.repository

import com.vastra.core.storage.TokenStore
import com.vastra.data.model.*
import com.vastra.data.remote.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val message: String, val code: Int = -1) : ApiResult<Nothing>()
}

@Singleton
class AuthRepository @Inject constructor(
    private val api: VastraApiService,
    private val tokenStore: TokenStore
) {
    suspend fun login(email: String, password: String): ApiResult<AuthResponse> = try {
        val response = api.login(LoginRequest(email, password))
        if (response.isSuccessful) {
            val body = response.body()!!
            tokenStore.saveToken(body.token)
            tokenStore.saveUserId(body.user.id)
            tokenStore.saveUsername(body.user.username)
            ApiResult.Success(body)
        } else ApiResult.Error(response.message(), response.code())
    } catch (e: Exception) {
        ApiResult.Error(e.message ?: "Network error")
    }

    suspend fun register(username: String, displayName: String, email: String, password: String): ApiResult<AuthResponse> = try {
        val response = api.register(RegisterRequest(username, displayName, email, password))
        if (response.isSuccessful) {
            val body = response.body()!!
            tokenStore.saveToken(body.token)
            tokenStore.saveUserId(body.user.id)
            tokenStore.saveUsername(body.user.username)
            ApiResult.Success(body)
        } else ApiResult.Error(response.message(), response.code())
    } catch (e: Exception) {
        ApiResult.Error(e.message ?: "Network error")
    }

    fun isLoggedIn() = tokenStore.isLoggedIn()
    fun logout() = tokenStore.clear()
}

@Singleton
class FeedRepository @Inject constructor(private val api: VastraApiService) {

    fun getFeed(page: Int = 0): Flow<ApiResult<FeedPage>> = flow {
        try {
            val response = api.getFeed(page)
            if (response.isSuccessful) emit(ApiResult.Success(response.body()!!))
            else emit(ApiResult.Error(response.message(), response.code()))
        } catch (e: Exception) {
            emit(ApiResult.Error(e.message ?: "Network error"))
        }
    }

    suspend fun likePost(postId: String): ApiResult<Unit> = try {
        val r = api.likePost(postId)
        if (r.isSuccessful) ApiResult.Success(Unit) else ApiResult.Error(r.message(), r.code())
    } catch (e: Exception) { ApiResult.Error(e.message ?: "Network error") }

    suspend fun savePost(postId: String): ApiResult<Unit> = try {
        val r = api.savePost(postId)
        if (r.isSuccessful) ApiResult.Success(Unit) else ApiResult.Error(r.message(), r.code())
    } catch (e: Exception) { ApiResult.Error(e.message ?: "Network error") }

    suspend fun getComments(postId: String): ApiResult<List<Comment>> = try {
        val r = api.getComments(postId)
        if (r.isSuccessful) ApiResult.Success(r.body()!!) else ApiResult.Error(r.message(), r.code())
    } catch (e: Exception) { ApiResult.Error(e.message ?: "Network error") }

    suspend fun addComment(postId: String, text: String): ApiResult<Comment> = try {
        val r = api.addComment(postId, AddCommentRequest(text))
        if (r.isSuccessful) ApiResult.Success(r.body()!!) else ApiResult.Error(r.message(), r.code())
    } catch (e: Exception) { ApiResult.Error(e.message ?: "Network error") }

    suspend fun recreateStyle(postId: String): ApiResult<RecreateStyleResponse> = try {
        val r = api.recreateStyle(postId)
        if (r.isSuccessful) ApiResult.Success(r.body()!!) else ApiResult.Error(r.message(), r.code())
    } catch (e: Exception) { ApiResult.Error(e.message ?: "Network error") }
}

@Singleton
class WardrobeRepository @Inject constructor(private val api: VastraApiService) {

    suspend fun getWardrobe(category: ClothingCategory? = null, status: OwnershipStatus? = null): ApiResult<List<ClothingItem>> = try {
        val r = api.getWardrobe(category?.name, status?.name)
        if (r.isSuccessful) ApiResult.Success(r.body()!!) else ApiResult.Error(r.message(), r.code())
    } catch (e: Exception) { ApiResult.Error(e.message ?: "Network error") }

    suspend fun scanItem(imageFile: File): ApiResult<ScanJobResponse> = try {
        val part = MultipartBody.Part.createFormData(
            "image", imageFile.name,
            imageFile.asRequestBody("image/*".toMediaType())
        )
        val r = api.scanItem(part)
        if (r.isSuccessful) ApiResult.Success(r.body()!!) else ApiResult.Error(r.message(), r.code())
    } catch (e: Exception) { ApiResult.Error(e.message ?: "Network error") }

    suspend fun pollScanJob(jobId: String): ApiResult<ScanJob> = try {
        val r = api.getScanStatus(jobId)
        if (r.isSuccessful) ApiResult.Success(r.body()!!) else ApiResult.Error(r.message(), r.code())
    } catch (e: Exception) { ApiResult.Error(e.message ?: "Network error") }

    suspend fun confirmScanItem(jobId: String, itemIndex: Int): ApiResult<ClothingItem> = try {
        val r = api.confirmScanItem(jobId, ConfirmScanItemRequest(itemIndex = itemIndex))
        if (r.isSuccessful) ApiResult.Success(r.body()!!) else ApiResult.Error(r.message(), r.code())
    } catch (e: Exception) { ApiResult.Error(e.message ?: "Network error") }

    suspend fun deleteItem(itemId: String): ApiResult<Unit> = try {
        val r = api.deleteItem(itemId)
        if (r.isSuccessful) ApiResult.Success(Unit) else ApiResult.Error(r.message(), r.code())
    } catch (e: Exception) { ApiResult.Error(e.message ?: "Network error") }
}

@Singleton
class RecommendationRepository @Inject constructor(private val api: VastraApiService) {

    suspend fun getNextSwipeCard(): ApiResult<ClothingItem> = try {
        val r = api.getNextSwipeCard()
        if (r.isSuccessful) ApiResult.Success(r.body()!!) else ApiResult.Error(r.message(), r.code())
    } catch (e: Exception) { ApiResult.Error(e.message ?: "Network error") }

    suspend fun recordSwipe(itemId: String, direction: SwipeDirection): ApiResult<Unit> = try {
        val r = api.recordSwipe(SwipeRequest(itemId, direction))
        if (r.isSuccessful) ApiResult.Success(Unit) else ApiResult.Error(r.message(), r.code())
    } catch (e: Exception) { ApiResult.Error(e.message ?: "Network error") }

    suspend fun getStyleRecommendations(): ApiResult<List<StyleRecommendation>> = try {
        val r = api.getStyleRecommendations()
        if (r.isSuccessful) ApiResult.Success(r.body()!!) else ApiResult.Error(r.message(), r.code())
    } catch (e: Exception) { ApiResult.Error(e.message ?: "Network error") }
}
