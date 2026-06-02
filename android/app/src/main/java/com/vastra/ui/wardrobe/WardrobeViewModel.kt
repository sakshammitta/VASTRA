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
    // User-edited category/subcategory per detected-item index (overrides CV guess)
    val editedCategories: Map<Int, ClothingCategory> = emptyMap(),
    val editedSubcategories: Map<Int, String> = emptyMap(),
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
        android.util.Log.d(
            "VastraScan",
            "scanImage() called: ${imageFile.absolutePath} exists=${imageFile.exists()} size=${imageFile.length()}B"
        )
        viewModelScope.launch {
            _uiState.update { it.copy(showAddSheet = false) }
            if (!imageFile.exists() || imageFile.length() == 0L) {
                _uiState.update { it.copy(error = "Selected image is empty or unreadable (size=${imageFile.length()}B). Try another photo.") }
                return@launch
            }
            when (val result = repo.scanItem(imageFile)) {
                is ApiResult.Success -> {
                    android.util.Log.d("VastraScan", "scanImage: job started jobId=${result.data.jobId}")
                    pollScanJob(result.data.jobId)
                }
                is ApiResult.Error -> {
                    android.util.Log.e("VastraScan", "scanImage error: ${result.message}")
                    _uiState.update { it.copy(error = result.message) }
                }
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
                                // Pre-select all items; user can deselect the wrong ones.
                                // Seed the editable fields with the CV predictions so the
                                // user only changes what's wrong (e.g. jacket → t-shirt).
                                val allIndices = job.detectedItems.indices.toSet()
                                val seedCats = job.detectedItems.mapIndexed { i, it -> i to it.category }.toMap()
                                val seedSubs = job.detectedItems.mapIndexed { i, it -> i to it.subCategory }.toMap()
                                _uiState.update { state ->
                                    state.copy(
                                        activeScanJobs = state.activeScanJobs - jobId,
                                        pendingConfirmJob = job,
                                        pendingJobId = jobId,
                                        selectedDetectedIndices = allIndices,
                                        editedCategories = seedCats,
                                        editedSubcategories = seedSubs
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

    fun setItemCategory(index: Int, category: ClothingCategory) {
        _uiState.update { it.copy(editedCategories = it.editedCategories + (index to category)) }
    }

    fun setItemSubcategory(index: Int, subCategory: String) {
        _uiState.update { it.copy(editedSubcategories = it.editedSubcategories + (index to subCategory)) }
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
            val edits = _uiState.value
            for (index in indices) {
                // Send the user-edited category/subcategory so a wrong CV guess
                // (e.g. jacket) is saved as the user's correction (e.g. t-shirt).
                repo.confirmScanItem(
                    jobId = jobId,
                    itemIndex = index,
                    category = edits.editedCategories[index],
                    subCategory = edits.editedSubcategories[index]?.takeIf { it.isNotBlank() }
                )
            }
            _uiState.update {
                it.copy(
                    isConfirming = false,
                    pendingConfirmJob = null,
                    pendingJobId = null,
                    selectedDetectedIndices = emptySet(),
                    editedCategories = emptyMap(),
                    editedSubcategories = emptyMap()
                )
            }
            loadWardrobe()
        }
    }

    fun dismissScanResult() = _uiState.update {
        it.copy(
            pendingConfirmJob = null,
            pendingJobId = null,
            selectedDetectedIndices = emptySet(),
            editedCategories = emptyMap(),
            editedSubcategories = emptyMap()
        )
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

    /** Surface a client-side error (e.g. failed image read) in the same snackbar. */
    fun reportScanError(message: String) = _uiState.update { it.copy(error = message) }

    fun clearError() = _uiState.update { it.copy(error = null) }
}
