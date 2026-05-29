package com.vastra.ui.recommendations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vastra.data.model.ClothingItem
import com.vastra.data.model.SwipeDirection
import com.vastra.data.repository.ApiResult
import com.vastra.data.repository.RecommendationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SwipeUiState(
    val cardStack: List<ClothingItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingNext: Boolean = false,
    val error: String? = null,
    val likeCount: Int = 0,
    val dislikeCount: Int = 0
)

@HiltViewModel
class SwipeViewModel @Inject constructor(
    private val repo: RecommendationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SwipeUiState())
    val uiState: StateFlow<SwipeUiState> = _uiState.asStateFlow()

    init {
        loadInitialStack()
    }

    private fun loadInitialStack() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            repeat(3) { loadNextCard() }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun loadNextCard() {
        when (val result = repo.getNextSwipeCard()) {
            is ApiResult.Success -> _uiState.update { state ->
                state.copy(cardStack = state.cardStack + result.data)
            }
            is ApiResult.Error -> _uiState.update { it.copy(error = result.message) }
        }
    }

    fun onSwipe(item: ClothingItem, direction: SwipeDirection) {
        viewModelScope.launch {
            _uiState.update { state ->
                val newStack = state.cardStack.drop(1)
                val likeCount = if (direction == SwipeDirection.LIKE || direction == SwipeDirection.SUPER_LIKE) state.likeCount + 1 else state.likeCount
                val dislikeCount = if (direction == SwipeDirection.DISLIKE) state.dislikeCount + 1 else state.dislikeCount
                state.copy(cardStack = newStack, likeCount = likeCount, dislikeCount = dislikeCount)
            }
            repo.recordSwipe(item.id, direction)
            _uiState.update { it.copy(isLoadingNext = true) }
            loadNextCard()
            _uiState.update { it.copy(isLoadingNext = false) }
        }
    }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
