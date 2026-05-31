package com.vastra.ui.wardrobe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vastra.data.model.ClothingCategory
import com.vastra.data.model.ClothingItem
import com.vastra.data.model.DetectedScanItem
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
    // Non-null while a scan is still polling
    val activeScanJobs: Map<String, ScanJob> = emptyMap(),
    // Non-null when scan is COMPLETE and user needs to pick which items to confirm
    val pendingConfirmJob: ScanJob? = null,
    val pendingJobId: String? = null,
    // Indices of detectedItems the user has checked
    val selectedDetectedIndices: Set<Int> = emptySet(),
    val isConfirming: Boolean = false
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
                is ApiResult.Success -> pollScanJob(result.data.jobId)
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
                                // Pre-select all items; user can deselect the wrong ones
                                val allIndices = job.detectedItems.indices.toSet()
                                _uiState.update { state ->
                                    state.copy(
                                        activeScanJobs = state.activeScanJobs - jobId,
                                        pendingConfirmJob = job,
                                        pendingJobId = jobId,
                                        selectedDetectedIndices = allIndices
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

    fun toggleDetectedItem(index: Int) {
        _uiState.update { state ->
            val current = state.selectedDetectedIndices
            val updated = if (index in current) current - index else current + index
            state.copy(selectedDetectedIndices = updated)
        }
    }

    fun confirmSelectedItems() {
        val state = _uiState.value
        val jobId = state.pendingJobId ?: return
        val indices = state.selectedDetectedIndices.sorted()
        if (indices.isEmpty()) {
            dismissScanResult()
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isConfirming = true) }
            for (index in indices) {
                repo.confirmScanItem(jobId, index)
            }
            _uiState.update { it.copy(isConfirming = false, pendingConfirmJob = null, pendingJobId = null, selectedDetectedIndices = emptySet()) }
            loadWardrobe()
        }
    }

    fun dismissScanResult() = _uiState.update {
        it.copy(pendingConfirmJob = null, pendingJobId = null, selectedDetectedIndices = emptySet())
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

    fun clearError() = _uiState.update { it.copy(error = null) }
}
