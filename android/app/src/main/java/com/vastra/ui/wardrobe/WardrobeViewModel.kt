package com.vastra.ui.wardrobe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vastra.data.model.ClothingCategory
import com.vastra.data.model.ClothingItem
import com.vastra.data.model.OwnershipStatus
import com.vastra.data.model.ScanJob
import com.vastra.data.model.ScanStatus
import com.vastra.data.repository.ApiResult
import com.vastra.data.repository.WardrobeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class WardrobeUiState(
    val items: List<ClothingItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedCategory: ClothingCategory? = null,
    val showAddSheet: Boolean = false,
    val activeScanJobs: Map<String, ScanJob> = emptyMap(),
    val showScanResult: ScanJob? = null
)

@HiltViewModel
class WardrobeViewModel @Inject constructor(
    private val repo: WardrobeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WardrobeUiState())
    val uiState: StateFlow<WardrobeUiState> = _uiState.asStateFlow()

    init { loadWardrobe() }

    fun loadWardrobe(category: ClothingCategory? = _uiState.value.selectedCategory) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = repo.getWardrobe(category, OwnershipStatus.OWNED)) {
                is ApiResult.Success -> _uiState.update { it.copy(items = result.data, isLoading = false) }
                is ApiResult.Error -> _uiState.update { it.copy(error = result.message, isLoading = false) }
            }
        }
    }

    fun selectCategory(category: ClothingCategory?) {
        _uiState.update { it.copy(selectedCategory = category) }
        loadWardrobe(category)
    }

    fun showAddSheet() = _uiState.update { it.copy(showAddSheet = true) }
    fun hideAddSheet() = _uiState.update { it.copy(showAddSheet = false) }

    fun scanImage(imageFile: File) {
        viewModelScope.launch {
            _uiState.update { it.copy(showAddSheet = false) }
            when (val result = repo.scanItem(imageFile)) {
                is ApiResult.Success -> {
                    val jobId = result.data.jobId
                    pollScanJob(jobId)
                }
                is ApiResult.Error -> _uiState.update { it.copy(error = result.message) }
            }
        }
    }

    private fun pollScanJob(jobId: String) {
        viewModelScope.launch {
            var attempts = 0
            while (attempts < 30) {
                delay(2000)
                when (val result = repo.pollScanJob(jobId)) {
                    is ApiResult.Success -> {
                        val job = result.data
                        _uiState.update { state ->
                            state.copy(activeScanJobs = state.activeScanJobs + (jobId to job))
                        }
                        when (job.status) {
                            ScanStatus.COMPLETE -> {
                                _uiState.update { state ->
                                    state.copy(
                                        activeScanJobs = state.activeScanJobs - jobId,
                                        showScanResult = job,
                                        items = state.items + job.detectedItems
                                    )
                                }
                                return@launch
                            }
                            ScanStatus.FAILED -> {
                                _uiState.update { state ->
                                    state.copy(
                                        activeScanJobs = state.activeScanJobs - jobId,
                                        error = job.errorMessage ?: "Scan failed"
                                    )
                                }
                                return@launch
                            }
                            else -> {}
                        }
                    }
                    is ApiResult.Error -> {
                        _uiState.update { it.copy(error = result.message) }
                        return@launch
                    }
                }
                attempts++
            }
            _uiState.update { state ->
                state.copy(activeScanJobs = state.activeScanJobs - jobId, error = "Scan timed out")
            }
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            when (repo.deleteItem(itemId)) {
                is ApiResult.Success -> _uiState.update { state ->
                    state.copy(items = state.items.filter { it.id != itemId })
                }
                is ApiResult.Error -> {}
            }
        }
    }

    fun dismissScanResult() = _uiState.update { it.copy(showScanResult = null) }
    fun clearError() = _uiState.update { it.copy(error = null) }
}
