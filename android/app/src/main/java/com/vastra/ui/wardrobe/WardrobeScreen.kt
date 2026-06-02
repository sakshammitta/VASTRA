package com.vastra.ui.wardrobe

import android.content.Context
import android.net.Uri
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.vastra.data.model.ClothingCategory
import com.vastra.data.model.ClothingItem
import com.vastra.data.model.DetectedScanItem
import com.vastra.ui.theme.*
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WardrobeScreen(
    onNavigateToScan: () -> Unit,
    viewModel: WardrobeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(VastraCream)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(top = 20.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        "YOUR CLOSET",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = VastraMutedText
                    )
                    Text(
                        "Wardrobe",
                        style = MaterialTheme.typography.headlineLarge,
                        color = VastraInk
                    )
                }
                OutlinedButton(
                    onClick = onNavigateToScan,
                    shape = RoundedCornerShape(20.dp),
                    border = BorderStroke(1.dp, VastraBorderColor),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VastraInk),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Scan", style = MaterialTheme.typography.labelMedium)
                }
            }

            // ── Stat cards ──────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatCard("Items", uiState.items.size.toString(), Modifier.weight(1f))
                StatCard("Looks", "—", Modifier.weight(1f))
                StatCard("Saved", "—", Modifier.weight(1f))
            }

            // ── Wardrobe Insights (disabled) ────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                color = VastraCard,
                border = BorderStroke(1.dp, VastraBorderColor)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.BarChart, null, tint = VastraMutedText, modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Wardrobe Insights",
                            style = MaterialTheme.typography.labelMedium,
                            color = VastraInk
                        )
                        Text(
                            "Add more items to unlock analytics",
                            style = MaterialTheme.typography.bodySmall,
                            color = VastraMutedText
                        )
                    }
                    Surface(color = VastraSand, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Soon",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = VastraMutedText
                        )
                    }
                }
            }

            // ── Style Advisor (dark panel, disabled) ────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp),
                shape = RoundedCornerShape(16.dp),
                color = VastraInk
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = VastraCream.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Style Advisor",
                            style = MaterialTheme.typography.labelMedium,
                            color = VastraCream
                        )
                        Text(
                            "AI outfit recommendations coming soon",
                            style = MaterialTheme.typography.bodySmall,
                            color = VastraCream.copy(alpha = 0.5f)
                        )
                    }
                    Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Soon",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = VastraCream.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Filter pills ────────────────────────────────────────────────
            CategoryFilterRow(
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = { viewModel.selectCategory(it) }
            )

            Spacer(Modifier.height(4.dp))

            // ── Grid ────────────────────────────────────────────────────────
            if (uiState.isLoading) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) { items(6) { WardrobeItemSkeleton() } }
            } else if (uiState.items.isEmpty()) {
                EmptyWardrobeState(onScan = onNavigateToScan)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
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

        // ── Error snackbar ───────────────────────────────────────────────────
        uiState.error?.let { msg ->
            Snackbar(
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                action = { TextButton(onClick = { viewModel.clearError() }) { Text("Dismiss") } }
            ) { Text(msg) }
        }

        // ── Scan result confirmation sheet ───────────────────────────────────
        uiState.pendingConfirmJob?.let { job ->
            ScanResultSelectionSheet(
                detectedItems = job.detectedItems,
                selectedIndices = uiState.selectedDetectedIndices,
                editedCategories = uiState.editedCategories,
                editedSubcategories = uiState.editedSubcategories,
                isConfirming = uiState.isConfirming,
                onToggle = { viewModel.toggleDetectedItem(it) },
                onCategoryChange = { i, c -> viewModel.setItemCategory(i, c) },
                onSubcategoryChange = { i, s -> viewModel.setItemSubcategory(i, s) },
                onConfirm = { viewModel.confirmSelectedItems() },
                onDismiss = { viewModel.dismissScanResult() }
            )
        }
    }
}

// ─── Stat card ───────────────────────────────────────────────────────────────

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = VastraCard,
        border = BorderStroke(1.dp, VastraBorderColor)
    ) {
        Column(
            modifier = Modifier.padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                color = VastraInk,
                textAlign = TextAlign.Center
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = VastraMutedText,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ─── Scan result selection bottom sheet ──────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanResultSelectionSheet(
    detectedItems: List<DetectedScanItem>,
    selectedIndices: Set<Int>,
    editedCategories: Map<Int, ClothingCategory>,
    editedSubcategories: Map<Int, String>,
    isConfirming: Boolean,
    onToggle: (Int) -> Unit,
    onCategoryChange: (Int, ClothingCategory) -> Unit,
    onSubcategoryChange: (Int, String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VastraCard,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // ── Fixed header ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Review detected items", style = MaterialTheme.typography.titleLarge, color = VastraInk)
                    Text(
                        "${detectedItems.size} piece${if (detectedItems.size == 1) "" else "s"} found · fix any wrong labels before saving",
                        style = MaterialTheme.typography.bodySmall,
                        color = VastraMutedText
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, "Dismiss", tint = VastraMutedText)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Scrollable item list ─────────────────────────────────────────
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
            ) {
                detectedItems.forEachIndexed { index, item ->
                    DetectedItemRow(
                        item = item,
                        isSelected = index in selectedIndices,
                        category = editedCategories[index] ?: item.category,
                        subCategory = editedSubcategories[index] ?: item.subCategory,
                        onToggle = { onToggle(index) },
                        onCategoryChange = { onCategoryChange(index, it) },
                        onSubcategoryChange = { onSubcategoryChange(index, it) }
                    )
                    if (index < detectedItems.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = VastraBorderColor)
                    }
                }
            }

            // ── Pinned confirm button ────────────────────────────────────────
            Spacer(Modifier.height(16.dp))

            val count = selectedIndices.size
            Button(
                onClick = onConfirm,
                enabled = count > 0 && !isConfirming,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = VastraInk)
            ) {
                if (isConfirming) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = VastraCream, strokeWidth = 2.dp)
                } else {
                    Text(
                        if (count == 0) "Select items to add"
                        else "Add $count ${if (count == 1) "item" else "items"} to wardrobe",
                        style = MaterialTheme.typography.labelLarge,
                        color = VastraCream
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetectedItemRow(
    item: DetectedScanItem,
    isSelected: Boolean,
    category: ClothingCategory,
    subCategory: String,
    onToggle: () -> Unit,
    onCategoryChange: (ClothingCategory) -> Unit,
    onSubcategoryChange: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(VastraMuted)
            ) {
                if (item.cropUrl != null) {
                    AsyncImage(
                        model = item.cropUrl,
                        contentDescription = subCategory.ifEmpty { category.name },
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Filled.Checkroom,
                        contentDescription = null,
                        modifier = Modifier.align(Alignment.Center).size(32.dp),
                        tint = VastraBorderColor
                    )
                }
            }

            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // Always encourage the user to verify the AI suggestion regardless of
                // confidence. The numeric score is kept internally but not shown — it
                // looked like an outfit/style match percentage to users.
                Text(
                    if (item.subtypeConfidence <= 0f) "AI suggestion · Verify type before saving"
                    else "AI suggestion · Please verify",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.subtypeConfidence <= 0f) VastraError else VastraMutedText
                )
                if (item.colorPalette.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item.colorPalette.take(3).forEach { hex ->
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColor(hex))
                                    .border(0.5.dp, VastraBorderColor, CircleShape)
                            )
                        }
                    }
                }
            }

            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(checkedColor = VastraInk, uncheckedColor = VastraBorderColor)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Editable category + subtype so a wrong prediction can be corrected.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            CategoryDropdown(
                selected = category,
                onSelected = onCategoryChange,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = subCategory,
                onValueChange = onSubcategoryChange,
                singleLine = true,
                label = { Text("Type", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("e.g. t-shirt") },
                textStyle = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VastraInk,
                    unfocusedBorderColor = VastraBorderColor
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryDropdown(
    selected: ClothingCategory,
    onSelected: (ClothingCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = selected.label,
            onValueChange = {},
            readOnly = true,
            label = { Text("Category", style = MaterialTheme.typography.labelSmall) },
            textStyle = MaterialTheme.typography.bodySmall,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = VastraInk,
                unfocusedBorderColor = VastraBorderColor
            ),
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ClothingCategory.values().forEach { cat ->
                DropdownMenuItem(
                    text = { Text(cat.label) },
                    onClick = {
                        onSelected(cat)
                        expanded = false
                    }
                )
            }
        }
    }
}

// ─── Wardrobe grid components ─────────────────────────────────────────────────

@Composable
fun CategoryFilterRow(selectedCategory: ClothingCategory?, onCategorySelected: (ClothingCategory?) -> Unit) {
    val categories = listOf(null) + ClothingCategory.values().toList()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        categories.forEach { category ->
            val isSelected = category == selectedCategory
            FilterChip(
                selected = isSelected,
                onClick = { onCategorySelected(category) },
                label = { Text(category?.label ?: "All", style = MaterialTheme.typography.labelMedium) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = VastraInk,
                    selectedLabelColor = VastraCream,
                    containerColor = VastraCard,
                    labelColor = VastraInk
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = VastraBorderColor,
                    selectedBorderColor = VastraInk
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
        colors = CardDefaults.cardColors(containerColor = VastraCard),
        elevation = CardDefaults.cardElevation(0.dp),
        border = BorderStroke(1.dp, VastraBorderColor)
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
                        color = Color.Black.copy(alpha = 0.55f),
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
                    item.brand?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = VastraMutedText, maxLines = 1) }
                    Text(
                        item.subCategory.ifEmpty { item.category.label },
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = VastraInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.colorPalette.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                            item.colorPalette.take(3).forEach { hex ->
                                Box(
                                    modifier = Modifier.size(12.dp).clip(CircleShape)
                                        .background(parseHexColor(hex))
                                        .border(0.5.dp, VastraBorderColor, CircleShape)
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
fun EmptyWardrobeState(onScan: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(40.dp)
        ) {
            Icon(Icons.Outlined.Checkroom, null, modifier = Modifier.size(64.dp), tint = VastraBorderColor)
            Text("Your closet is empty", style = MaterialTheme.typography.titleMedium, color = VastraInk)
            Text(
                "Scan your first piece to start building your digital wardrobe",
                style = MaterialTheme.typography.bodyMedium,
                color = VastraMutedText,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = onScan,
                colors = ButtonDefaults.buttonColors(containerColor = VastraInk),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Scan a piece")
            }
        }
    }
}

@Composable
fun WardrobeItemSkeleton() {
    val alpha by rememberInfiniteTransition(label = "shimmer").animateFloat(
        0.3f, 0.7f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "shimmer"
    )
    Card(
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(0.dp),
        border = BorderStroke(1.dp, VastraBorderColor)
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(0.75f).background(VastraMuted.copy(alpha = alpha)))
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.width(60.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(VastraMuted.copy(alpha = alpha)))
                Box(Modifier.width(100.dp).height(12.dp).clip(RoundedCornerShape(5.dp)).background(VastraMuted.copy(alpha = alpha)))
            }
        }
    }
}

private fun parseHexColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
} catch (e: Exception) { Color.LightGray }

/** Copy a content URI to a temporary file so it can be sent as a multipart body. */
fun Uri.toTempFile(context: Context): File {
    val t0 = System.currentTimeMillis()
    val mime = context.contentResolver.getType(this)
    val input = context.contentResolver.openInputStream(this)!!
    val suffix = when (mime) {
        "image/png"  -> ".png"
        "image/webp" -> ".webp"
        else         -> ".jpg"
    }
    val tmp = File.createTempFile("scan_", suffix, context.cacheDir)
    tmp.outputStream().use { input.copyTo(it) }
    android.util.Log.d(
        "VastraScan",
        "timing uri→file: ${System.currentTimeMillis() - t0}ms  mime=$mime size=${tmp.length()}B"
    )
    return tmp
}
