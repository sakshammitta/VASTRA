package com.vastra.ui.wardrobe

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.vastra.data.model.ClothingCategory
import com.vastra.data.model.ClothingItem
import com.vastra.data.model.ScanStatus
import com.vastra.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WardrobeScreen(viewModel: WardrobeViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(VastraCream)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("My Wardrobe", style = MaterialTheme.typography.headlineMedium, color = VastraCharcoal)
                    Text("${uiState.items.size} items", style = MaterialTheme.typography.bodySmall, color = VastraSubtext)
                }
                if (uiState.activeScanJobs.isNotEmpty()) {
                    ScanningIndicator()
                }
            }

            CategoryFilterRow(
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = { viewModel.selectCategory(it) }
            )

            if (uiState.isLoading) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(6) { WardrobeItemSkeleton() }
                }
            } else if (uiState.items.isEmpty()) {
                EmptyWardrobeState(onAddItem = { viewModel.showAddSheet() })
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(uiState.items, key = { it.id }) { item ->
                        ClothingItemCard(item = item, onDelete = { viewModel.deleteItem(item.id) })
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { viewModel.showAddSheet() },
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            containerColor = VastraCharcoal,
            contentColor = VastraCream
        ) {
            Icon(Icons.Filled.Add, "Add Item")
        }

        if (uiState.showAddSheet) {
            AddItemBottomSheet(
                onDismiss = { viewModel.hideAddSheet() },
                onScanFromCamera = { viewModel.hideAddSheet() },
                onPickFromGallery = { viewModel.hideAddSheet() },
                onManualEntry = { viewModel.hideAddSheet() },
                onImportZara = { viewModel.hideAddSheet() },
                onImportHM = { viewModel.hideAddSheet() }
            )
        }

        uiState.showScanResult?.let { job ->
            AlertDialog(
                onDismissRequest = { viewModel.dismissScanResult() },
                containerColor = VastraSurface,
                title = { Text("Scan Complete!", style = MaterialTheme.typography.headlineSmall) },
                text = {
                    Text(
                        "Found ${job.detectedItems.size} clothing ${if (job.detectedItems.size == 1) "item" else "items"} in your photo.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.dismissScanResult() }) {
                        Text("Great!", color = VastraGold)
                    }
                }
            )
        }
    }
}

@Composable
fun CategoryFilterRow(selectedCategory: ClothingCategory?, onCategorySelected: (ClothingCategory?) -> Unit) {
    val categories = listOf(null) + ClothingCategory.values().toList()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { category ->
            val isSelected = category == selectedCategory
            FilterChip(
                selected = isSelected,
                onClick = { onCategorySelected(category) },
                label = { Text(category?.label ?: "All", style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = VastraCharcoal,
                    selectedLabelColor = VastraCream,
                    containerColor = VastraSurface,
                    labelColor = VastraCharcoal
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true, selected = isSelected,
                    borderColor = VastraOutline, selectedBorderColor = VastraCharcoal
                )
            )
        }
    }
}

@Composable
fun ClothingItemCard(item: ClothingItem, onDelete: () -> Unit) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = VastraSurface),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Box {
            Column {
                Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.75f)) {
                    AsyncImage(
                        model = item.imageUrl,
                        contentDescription = item.subCategory,
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Surface(
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            item.category.label,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Column(modifier = Modifier.padding(10.dp)) {
                    item.brand?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = VastraSubtext, maxLines = 1) }
                    Text(
                        item.subCategory.ifEmpty { item.category.label },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = VastraCharcoal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.colorPalette.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                            item.colorPalette.take(3).forEach { hex ->
                                Box(
                                    modifier = Modifier.size(12.dp).clip(CircleShape)
                                        .background(parseHexColor(hex))
                                        .border(0.5.dp, VastraOutline, CircleShape)
                                )
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Filled.MoreVert, null, tint = Color.White, modifier = Modifier.size(16.dp))
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { showMenu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
fun ScanningIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "scan")
    val alpha by infiniteTransition.animateFloat(
        0.4f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "scan"
    )
    Surface(color = VastraGold.copy(alpha = alpha), shape = RoundedCornerShape(12.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Color.White, strokeWidth = 2.dp)
            Text("Scanning…", color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddItemBottomSheet(
    onDismiss: () -> Unit,
    onScanFromCamera: () -> Unit,
    onPickFromGallery: () -> Unit,
    onManualEntry: () -> Unit,
    onImportZara: () -> Unit,
    onImportHM: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VastraSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Add to Wardrobe", style = MaterialTheme.typography.titleLarge, color = VastraCharcoal, modifier = Modifier.padding(bottom = 24.dp))
            listOf(
                Triple(Icons.Filled.CameraAlt, "Scan with Camera", onScanFromCamera),
                Triple(Icons.Filled.PhotoLibrary, "Choose from Gallery", onPickFromGallery),
                Triple(Icons.Filled.Edit, "Enter Manually", onManualEntry),
            ).forEach { (icon, label, action) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(icon, null, tint = VastraCharcoal, modifier = Modifier.size(22.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = VastraCharcoal)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = VastraOutline)
            Text("Import Purchase History", style = MaterialTheme.typography.labelMedium, color = VastraSubtext, modifier = Modifier.padding(bottom = 8.dp))
            listOf(
                Triple(Icons.Filled.ShoppingBag, "Import from Zara", onImportZara),
                Triple(Icons.Filled.ShoppingBag, "Import from H&M", onImportHM),
            ).forEach { (icon, label, action) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Icon(icon, null, tint = VastraSubtext, modifier = Modifier.size(20.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium, color = VastraCharcoal)
                    Spacer(Modifier.weight(1f))
                    Surface(color = VastraGoldLight, shape = RoundedCornerShape(8.dp)) {
                        Text("Connect", modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp), style = MaterialTheme.typography.labelSmall, color = VastraGoldDark)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyWardrobeState(onAddItem: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Filled.Checkroom, null, modifier = Modifier.size(72.dp), tint = VastraOutline)
            Text("Your wardrobe is empty", style = MaterialTheme.typography.titleMedium, color = VastraCharcoal)
            Text("Add your first item to get started", style = MaterialTheme.typography.bodyMedium, color = VastraSubtext)
            Button(onClick = onAddItem, colors = ButtonDefaults.buttonColors(containerColor = VastraCharcoal)) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Add Item")
            }
        }
    }
}

@Composable
fun WardrobeItemSkeleton() {
    val shimmer = rememberInfiniteTransition(label = "shimmer")
    val alpha by shimmer.animateFloat(0.3f, 0.7f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "shimmer")
    Card(shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(0.dp)) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(VastraSurfaceVariant.copy(alpha = alpha)))
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(60.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(VastraSurfaceVariant.copy(alpha = alpha)))
                Box(Modifier.width(100.dp).height(12.dp).clip(RoundedCornerShape(5.dp)).background(VastraSurfaceVariant.copy(alpha = alpha)))
            }
        }
    }
}

private fun parseHexColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
} catch (e: Exception) { Color.LightGray }
