package com.vastra.ui.feed

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vastra.data.model.Comment
import com.vastra.data.model.Post
import com.vastra.data.model.RecreateStyleResponse
import com.vastra.data.repository.ApiResult
import com.vastra.data.repository.FeedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedUiState(
    val posts: List<Post> = emptyList(),
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 0,
    val error: String? = null,
    val activeCommentPostId: String? = null,
    val comments: List<Comment> = emptyList(),
    val isLoadingComments: Boolean = false,
    val recreateResult: RecreateStyleResponse? = null,
    val recreatingPostId: String? = null
)

@HiltViewModel
class FeedViewModel @Inject constructor(
    private val repo: FeedRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedUiState())
    val uiState: StateFlow<FeedUiState> = _uiState.asStateFlow()

    init { loadFeed() }

    fun loadFeed() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, currentPage = 0, error = null) }
            repo.getFeed(0).collect { result ->
                when (result) {
                    is ApiResult.Success -> _uiState.update { state ->
                        state.copy(
                            posts = result.data.content,
                            isLoading = false,
                            hasMore = result.data.hasNext,
                            currentPage = 0
                        )
                    }
                    is ApiResult.Error -> _uiState.update { it.copy(isLoading = false, error = result.message) }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            repo.getFeed(0).collect { result ->
                when (result) {
                    is ApiResult.Success -> _uiState.update { state ->
                        state.copy(
                            posts = result.data.content,
                            isRefreshing = false,
                            hasMore = result.data.hasNext,
                            currentPage = 0
                        )
                    }
                    is ApiResult.Error -> _uiState.update { it.copy(isRefreshing = false, error = result.message) }
                }
            }
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoadingMore || !state.hasMore) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val nextPage = state.currentPage + 1
            repo.getFeed(nextPage).collect { result ->
                when (result) {
                    is ApiResult.Success -> _uiState.update { s ->
                        s.copy(
                            posts = s.posts + result.data.content,
                            isLoadingMore = false,
                            hasMore = result.data.hasNext,
                            currentPage = nextPage
                        )
                    }
                    is ApiResult.Error -> _uiState.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    fun likePost(postId: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(posts = state.posts.map { p ->
                    if (p.id == postId) p.copy(isLikedByMe = !p.isLikedByMe, likeCount = if (!p.isLikedByMe) p.likeCount + 1 else p.likeCount - 1) else p
                })
            }
            repo.likePost(postId)
        }
    }

    fun savePost(postId: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(posts = state.posts.map { p ->
                    if (p.id == postId) p.copy(isSavedByMe = !p.isSavedByMe) else p
                })
            }
            repo.savePost(postId)
        }
    }

    fun openComments(postId: String) {
        _uiState.update { it.copy(activeCommentPostId = postId, comments = emptyList()) }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingComments = true) }
            when (val result = repo.getComments(postId)) {
                is ApiResult.Success -> _uiState.update { it.copy(comments = result.data, isLoadingComments = false) }
                is ApiResult.Error -> _uiState.update { it.copy(isLoadingComments = false) }
            }
        }
    }

    fun closeComments() = _uiState.update { it.copy(activeCommentPostId = null, comments = emptyList()) }

    fun addComment(postId: String, text: String) {
        viewModelScope.launch {
            when (val result = repo.addComment(postId, text)) {
                is ApiResult.Success -> _uiState.update { state ->
                    state.copy(
                        comments = state.comments + result.data,
                        posts = state.posts.map { p ->
                            if (p.id == postId) p.copy(commentCount = p.commentCount + 1) else p
                        }
                    )
                }
                is ApiResult.Error -> _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    fun recreateStyle(postId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(recreatingPostId = postId) }
            when (val result = repo.recreateStyle(postId)) {
                is ApiResult.Success -> _uiState.update { it.copy(recreateResult = result.data, recreatingPostId = null) }
                is ApiResult.Error -> _uiState.update { it.copy(recreatingPostId = null, error = result.message) }
            }
        }
    }

    fun dismissRecreateResult() = _uiState.update { it.copy(recreateResult = null) }
    fun clearError() = _uiState.update { it.copy(error = null) }
}
