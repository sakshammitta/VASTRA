package com.vastra.ui.saved

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.SecondaryIndicator
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vastra.data.model.ClothingItem
import com.vastra.data.model.OwnershipStatus
import com.vastra.data.model.ScanJob
import com.vastra.data.model.ScanStatus
import com.vastra.data.repository.ApiResult
import com.vastra.data.repository.WardrobeRepository
import com.vastra.ui.theme.*
import com.vastra.ui.wardrobe.ClothingItemCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class SavedUiState(
    val wishlistItems: List<ClothingItem> = emptyList(),
    val scanHistory: List<ScanJob> = emptyList(),
    val isLoading: Boolean = false,
    val activeTab: Int = 0,
    val showUploadSheet: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SavedViewModel @Inject constructor(
    private val repo: WardrobeRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(SavedUiState())
    val uiState: StateFlow<SavedUiState> = _uiState.asStateFlow()

    init { loadWishlist() }

    fun loadWishlist() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            when (val result = repo.getWardrobe(null, OwnershipStatus.ASPIRATIONAL)) {
                is ApiResult.Success -> _uiState.update { it.copy(wishlistItems = result.data, isLoading = false) }
                is ApiResult.Error -> _uiState.update { it.copy(error = result.message, isLoading = false) }
            }
        }
    }

    fun selectTab(index: Int) = _uiState.update { it.copy(activeTab = index) }
    fun showUploadSheet() = _uiState.update { it.copy(showUploadSheet = true) }
    fun hideUploadSheet() = _uiState.update { it.copy(showUploadSheet = false) }
    fun clearError() = _uiState.update { it.copy(error = null) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(viewModel: SavedViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(VastraCream)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Saved", style = MaterialTheme.typography.headlineMedium, color = VastraCharcoal)
            }

            TabRow(
                selectedTabIndex = uiState.activeTab,
                containerColor = VastraCream,
                contentColor = VastraCharcoal,
                indicator = { tabPositions ->
                    SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[uiState.activeTab]),
                        color = VastraGold
                    )
                }
            ) {
                Tab(selected = uiState.activeTab == 0, onClick = { viewModel.selectTab(0) },
                    text = { Text("Wishlist") })
                Tab(selected = uiState.activeTab == 1, onClick = { viewModel.selectTab(1) },
                    text = { Text("Scan History") })
            }

            when (uiState.activeTab) {
                0 -> WishlistTab(items = uiState.wishlistItems, isLoading = uiState.isLoading)
                1 -> ScanHistoryTab(jobs = uiState.scanHistory)
            }
        }

        FloatingActionButton(
            onClick = { viewModel.showUploadSheet() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            containerColor = VastraCharcoal,
            contentColor = VastraCream
        ) {
            Icon(Icons.Filled.Upload, "Upload Screenshot")
        }

        if (uiState.showUploadSheet) {
            UploadScreenshotSheet(onDismiss = { viewModel.hideUploadSheet() })
        }
    }
}

@Composable
fun WishlistTab(items: List<ClothingItem>, isLoading: Boolean) {
    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = VastraGold)
        }
    } else if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.BookmarkBorder, null, modifier = Modifier.size(64.dp), tint = VastraOutline)
                Text("No saved items yet", style = MaterialTheme.typography.titleMedium, color = VastraCharcoal)
                Text("Save outfits from the feed or upload screenshots", style = MaterialTheme.typography.bodySmall, color = VastraSubtext)
            }
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(items, key = { it.id }) { item ->
                ClothingItemCard(item = item, onDelete = {})
            }
        }
    }
}

@Composable
fun ScanHistoryTab(jobs: List<ScanJob>) {
    if (jobs.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Filled.History, null, modifier = Modifier.size(64.dp), tint = VastraOutline)
                Text("No scans yet", style = MaterialTheme.typography.titleMedium, color = VastraCharcoal)
                Text("Upload a Pinterest or Instagram screenshot to get started", style = MaterialTheme.typography.bodySmall, color = VastraSubtext)
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            jobs.forEach { job ->
                ScanJobCard(job = job)
            }
        }
    }
}

@Composable
fun ScanJobCard(job: ScanJob) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = VastraSurface)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val (icon, color) = when (job.status) {
                ScanStatus.COMPLETE -> Icons.Filled.CheckCircle to VastraSuccess
                ScanStatus.FAILED -> Icons.Filled.Error to VastraError
                ScanStatus.PROCESSING -> Icons.Filled.Sync to VastraGold
                ScanStatus.QUEUED -> Icons.Filled.Schedule to VastraSubtext
            }
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(job.status.name, style = MaterialTheme.typography.labelMedium, color = VastraCharcoal)
                if (job.detectedItems.isNotEmpty()) {
                    Text("${job.detectedItems.size} items found", style = MaterialTheme.typography.bodySmall, color = VastraSubtext)
                }
                job.errorMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VastraError) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UploadScreenshotSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VastraSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp).padding(bottom = 32.dp)) {
            Text("Upload Screenshot", style = MaterialTheme.typography.titleLarge, color = VastraCharcoal)
            Spacer(Modifier.height(8.dp))
            Text("Upload a Pinterest or Instagram screenshot to extract and save clothing items", style = MaterialTheme.typography.bodyMedium, color = VastraSubtext)
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = VastraCharcoal),
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Filled.Upload, null)
                Spacer(Modifier.width(8.dp))
                Text("Choose Screenshot")
            }
        }
    }
}
