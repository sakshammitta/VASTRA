package com.vastra.ui.wardrobe

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vastra.data.model.ClothingCategory
import com.vastra.data.model.ClothingItem
import com.vastra.data.model.DetectedScanItem
import com.vastra.data.model.OwnershipStatus
import com.vastra.data.model.ScanJob
import com.vastra.data.model.ScanStatus
import com.vastra.data.model.WebMatchCandidate
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

/**
 * Explicit display-image state for a single detected item in the review sheet.
 *
 * Only [WebConfirmed] or [AiRenderConfirmed] may become the final wardrobe image.
 * Every other state saves the item as PENDING (placeholder shown, never the crop).
 *
 * Transitions:
 *   SearchingWeb ─► WebCandidatesAvailable ─(Yes)─► WebConfirmed
 *                                          ─(Show alternatives)─► WebCandidatesAvailable (next candidate)
 *                                          ─(None of these)─► GeneratingAiRender
 *               ─► DisplayImagePending (no candidates / not configured) ─► GeneratingAiRender
 *   GeneratingAiRender ─► AiRenderReadyForApproval ─(Use this)─► AiRenderConfirmed
 *                                                  ─(Regenerate)─► GeneratingAiRender
 *                                                  ─(Try another match)─► WebCandidatesAvailable / SearchingWeb
 *                      ─► DisplayImagePending (render failed / not configured)
 */
sealed class ImageSourceState {
    /** SERPAPI Google Lens search in progress. */
    object SearchingWeb : ImageSourceState()

    /**
     * One or more visual-match candidates returned. [shownIndex] selects which
     * candidate is currently displayed; "Show alternatives" advances it.
     * NEVER auto-confirmed — the user must explicitly tap "Yes, use this".
     */
    data class WebCandidatesAvailable(
        val candidates: List<WebMatchCandidate>,
        val shownIndex: Int = 0
    ) : ImageSourceState() {
        val current: WebMatchCandidate get() = candidates[shownIndex]
        val hasMore: Boolean get() = candidates.size > 1
    }

    /** User confirmed a web match candidate — eligible to be the final image. */
    data class WebConfirmed(val candidate: WebMatchCandidate) : ImageSourceState()

    /** gpt-image-2 render in progress. */
    object GeneratingAiRender : ImageSourceState()

    /**
     * Render complete and awaiting approval. NOT saved automatically — the user
     * must tap "Use this clean image". renderUrl previews it; renderKey is the R2 key.
     */
    data class AiRenderReadyForApproval(val renderUrl: String, val renderKey: String) : ImageSourceState()

    /** User approved the AI render — eligible to be the final image. */
    data class AiRenderConfirmed(val renderUrl: String, val renderKey: String) : ImageSourceState()

    /**
     * No clean display image available (no web candidates AND render failed or
     * neither service configured). The item will save as PENDING and show a
     * placeholder — never the raw crop. [canRetry] enables a manual retry button.
     */
    data class DisplayImagePending(val reason: String, val canRetry: Boolean = true) : ImageSourceState()
}

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
    val isConfirming: Boolean = false,
    // Per-item image-source state (keyed by detected-item index)
    val imageSourceStates: Map<Int, ImageSourceState> = emptyMap(),
    // Temporary on-screen scan-flow trace (visible debug panel on the Scan screen).
    val scanStatus: String? = null,
    val scanDebug: String? = null,
    // Set when a scan request returns HTTP 401 — prompt the user to sign in again.
    val sessionExpired: Boolean = false
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
            val t0Total = System.currentTimeMillis()
            _uiState.update {
                it.copy(
                    showAddSheet = false,
                    error = null,
                    scanStatus = "Preparing upload file",
                    scanDebug = "file=${imageFile.name} size=${imageFile.length()}B"
                )
            }
            if (!imageFile.exists() || imageFile.length() == 0L) {
                setScanError(
                    "Detection failed: selected image is empty or unreadable",
                    "exists=${imageFile.exists()} size=${imageFile.length()}B path=${imageFile.absolutePath}"
                )
                return@launch
            }
            _uiState.update { it.copy(scanStatus = "Uploading to backend") }
            when (val result = repo.scanItem(imageFile)) {
                is ApiResult.Success -> {
                    val jobId = result.data.jobId
                    android.util.Log.d("VastraScan", "timing upload+accept: jobId=$jobId elapsed=${System.currentTimeMillis() - t0Total}ms")
                    _uiState.update {
                        it.copy(scanStatus = "Scan job started", scanDebug = "jobId=$jobId")
                    }
                    pollScanJob(jobId, t0Total)
                }
                is ApiResult.Error -> {
                    android.util.Log.e("VastraScan", "scanImage error [${result.code}]: ${result.message}")
                    if (result.code == 401) handleSessionExpired(result.message)
                    else setScanError("Detection failed", result.message)
                }
            }
        }
    }

    private fun setScanError(status: String, debug: String?) {
        _uiState.update { it.copy(scanStatus = status, scanDebug = debug, error = "$status: ${debug ?: ""}") }
    }

    private fun handleSessionExpired(debug: String?) {
        android.util.Log.w("VastraScan", "session expired (401): $debug")
        _uiState.update {
            it.copy(
                sessionExpired = true,
                scanStatus = "Session expired",
                scanDebug = debug,
                error = "Your session expired. Please sign in again."
            )
        }
    }

    private fun pollScanJob(jobId: String, t0Total: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            _uiState.update { it.copy(scanStatus = "Waiting for detection") }
            var attempts = 0
            while (attempts < 40) {
                // First two polls are quick (1 s) to catch fast results; then 2 s.
                delay(if (attempts < 2) 1000L else 2000L)
                when (val result = repo.pollScanJob(jobId)) {
                    is ApiResult.Success -> {
                        val job = result.data
                        _uiState.update { state ->
                            state.copy(
                                activeScanJobs = state.activeScanJobs + (jobId to job),
                                scanStatus = "Waiting for detection (${job.status}, ${attempts + 1})"
                            )
                        }
                        when (job.status) {
                            ScanStatus.COMPLETE -> {
                                val totalMs = System.currentTimeMillis() - t0Total
                                android.util.Log.d(
                                    "VastraScan",
                                    "timing TOTAL gallery→review: ${totalMs}ms  " +
                                        "items=${job.detectedItems.size}  polls=$attempts"
                                )
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
                                        editedSubcategories = seedSubs,
                                        imageSourceStates = emptyMap(),
                                        scanStatus = "Detected ${job.detectedItems.size} item(s)",
                                        scanDebug = job.detectedItems.joinToString { "${it.category}/${it.subCategory}" }
                                    )
                                }
                                // Auto-start web match search for every detected item
                                job.detectedItems.indices.forEach { i -> requestWebMatch(jobId, i) }
                                return@launch
                            }
                            ScanStatus.FAILED -> {
                                setScanError("Detection failed", job.errorMessage ?: "Scan failed")
                                _uiState.update { state ->
                                    state.copy(activeScanJobs = state.activeScanJobs - jobId)
                                }
                                return@launch
                            }
                            else -> {}
                        }
                    }
                    is ApiResult.Error -> {
                        if (result.code == 401) handleSessionExpired(result.message)
                        else setScanError("Detection failed (poll)", result.message)
                        return@launch
                    }
                }
                attempts++
            }
            setScanError("Detection failed", "Scan timed out after $attempts polls")
            _uiState.update { state -> state.copy(activeScanJobs = state.activeScanJobs - jobId) }
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
                // Only an explicitly confirmed web match or approved AI render
                // becomes the display image. Anything else saves as PENDING.
                val imgState = edits.imageSourceStates[index]
                val webCandidate = (imgState as? ImageSourceState.WebConfirmed)?.candidate
                val aiRenderKey  = (imgState as? ImageSourceState.AiRenderConfirmed)?.renderKey
                repo.confirmScanItem(
                    jobId = jobId,
                    itemIndex = index,
                    category = edits.editedCategories[index],
                    subCategory = edits.editedSubcategories[index]?.takeIf { it.isNotBlank() },
                    webMatchImageUrl = webCandidate?.imageUrl,
                    webMatchSourceUrl = webCandidate?.sourceUrl,
                    aiRenderKey = aiRenderKey
                )
            }
            _uiState.update {
                it.copy(
                    isConfirming = false,
                    pendingConfirmJob = null,
                    pendingJobId = null,
                    selectedDetectedIndices = emptySet(),
                    editedCategories = emptyMap(),
                    editedSubcategories = emptyMap(),
                    imageSourceStates = emptyMap()
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
            editedSubcategories = emptyMap(),
            imageSourceStates = emptyMap()
        )
    }

    // ── Image source methods ─────────────────────────────────────────────────

    private fun setImageState(index: Int, state: ImageSourceState) {
        _uiState.update { it.copy(imageSourceStates = it.imageSourceStates + (index to state)) }
    }

    /**
     * Start the SerpAPI Google Lens search. Auto-started on scan complete; safe
     * to call again ("Try another match"). Sends only the garment crop URL.
     * Candidates are NEVER auto-confirmed — the user must tap "Yes, use this".
     */
    fun requestWebMatch(jobId: String, index: Int) {
        setImageState(index, ImageSourceState.SearchingWeb)
        viewModelScope.launch {
            when (val result = repo.requestWebMatch(jobId, index)) {
                is ApiResult.Success -> {
                    val r = result.data
                    when {
                        !r.available ->
                            // SerpAPI not configured — go straight to AI render path.
                            requestAiRender(index)
                        r.candidates.isEmpty() ->
                            // No visual matches — fall through to AI render.
                            requestAiRender(index)
                        else ->
                            setImageState(index, ImageSourceState.WebCandidatesAvailable(r.candidates))
                    }
                }
                is ApiResult.Error -> requestAiRender(index)
            }
        }
    }

    /** "Show alternatives" — cycle to the next web candidate without confirming. */
    fun showNextWebCandidate(index: Int) {
        val cur = _uiState.value.imageSourceStates[index]
        if (cur is ImageSourceState.WebCandidatesAvailable) {
            val next = (cur.shownIndex + 1) % cur.candidates.size
            setImageState(index, cur.copy(shownIndex = next))
        }
    }

    /** "Yes, use this" — confirm the currently-shown web candidate. */
    fun confirmWebMatch(index: Int) {
        val cur = _uiState.value.imageSourceStates[index]
        if (cur is ImageSourceState.WebCandidatesAvailable) {
            setImageState(index, ImageSourceState.WebConfirmed(cur.current))
        }
    }

    /** "None of these — generate clean image" — abandon web match, start render. */
    fun rejectWebMatch(index: Int) {
        requestAiRender(index)
    }

    /** Start (or "Regenerate") a gpt-image-2 render. Awaits user approval. */
    fun requestAiRender(index: Int) {
        val jobId = _uiState.value.pendingJobId ?: return
        setImageState(index, ImageSourceState.GeneratingAiRender)
        viewModelScope.launch {
            when (val result = repo.requestAiRender(jobId, index)) {
                is ApiResult.Success -> {
                    val r = result.data
                    if (!r.available || r.renderUrl == null || r.renderKey == null) {
                        val reason = if (!r.available)
                            "Clean image unavailable · set SERPAPI_KEY and OPENAI_API_KEY to enable"
                        else
                            "Couldn't generate a clean image"
                        setImageState(index, ImageSourceState.DisplayImagePending(reason))
                    } else {
                        setImageState(index, ImageSourceState.AiRenderReadyForApproval(r.renderUrl, r.renderKey))
                    }
                }
                is ApiResult.Error ->
                    setImageState(index, ImageSourceState.DisplayImagePending("Couldn't generate a clean image"))
            }
        }
    }

    /** "Use this clean image" — approve the AI render as the final display image. */
    fun confirmAiRender(index: Int) {
        val cur = _uiState.value.imageSourceStates[index]
        if (cur is ImageSourceState.AiRenderReadyForApproval) {
            setImageState(index, ImageSourceState.AiRenderConfirmed(cur.renderUrl, cur.renderKey))
        }
    }

    /** "Try another match" — go back to web candidates (if any) or re-search. */
    fun tryAnotherMatch(index: Int) {
        val jobId = _uiState.value.pendingJobId ?: return
        requestWebMatch(jobId, index)
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

    /** Surface a client-side error (e.g. failed image read) in the visible panel. */
    fun reportScanError(message: String) =
        _uiState.update { it.copy(error = message, scanStatus = "Detection failed", scanDebug = message) }

    /** Update the visible scan-flow status panel (temporary debug aid). */
    fun reportScanStatus(status: String, debug: String? = null) =
        _uiState.update { it.copy(scanStatus = status, scanDebug = debug, error = null) }

    fun clearError() = _uiState.update { it.copy(error = null, sessionExpired = false) }
}
