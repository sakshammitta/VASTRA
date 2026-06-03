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
                        ClothingItemCard(
                            item = item,
                            onDelete = { viewModel.deleteItem(item.id) },
                            onEnhance = { viewModel.openEnhanceSheet(item) }
                        )
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
                imageSourceStates = uiState.imageSourceStates,
                isConfirming = uiState.isConfirming,
                onToggle = { viewModel.toggleDetectedItem(it) },
                onCategoryChange = { i, c -> viewModel.setItemCategory(i, c) },
                onSubcategoryChange = { i, s -> viewModel.setItemSubcategory(i, s) },
                onStartCleanImages = { viewModel.startCleanImageForSelected() },
                onConfirmWebMatch = { viewModel.confirmWebMatch(it) },
                onShowNextWebCandidate = { viewModel.showNextWebCandidate(it) },
                onRejectWebMatch = { viewModel.rejectWebMatch(it) },
                onConfirmAiRender = { viewModel.confirmAiRender(it) },
                onRegenerateAiRender = { viewModel.requestAiRender(it) },
                onTryAnotherMatch = { viewModel.tryAnotherMatch(it) },
                onConfirm = { viewModel.confirmSelectedItems() },
                onDismiss = { viewModel.dismissScanResult() }
            )
        }

        // ── Enhance-a-saved-item sheet (clean image for an existing PENDING item)
        uiState.enhancingItem?.let { item ->
            EnhanceItemSheet(
                item = item,
                state = uiState.enhanceImageState,
                isSaving = uiState.isSavingDisplayImage,
                onStartWebMatch = { viewModel.enhanceStartWebMatch() },
                onStartAiRender = { viewModel.enhanceStartAiRenderDirect() },
                onConfirmWebMatch = { viewModel.enhanceConfirmWebMatch() },
                onShowNextWebCandidate = { viewModel.enhanceShowNextCandidate() },
                onRejectWebMatch = { viewModel.enhanceRejectWebMatch() },
                onConfirmAiRender = { viewModel.enhanceConfirmAiRender() },
                onRegenerateAiRender = { viewModel.enhanceRequestAiRender() },
                onTryAnotherMatch = { viewModel.enhanceTryAnotherMatch() },
                onDismiss = { viewModel.closeEnhanceSheet() }
            )
        }
    }
}

/**
 * Bottom sheet to enhance an ALREADY-SAVED wardrobe item with a clean display
 * image — without rescanning. Reuses the same [ImageSourceSection] state machine
 * as the scan review flow. Confirming a web match or approving an AI render
 * commits immediately (the sheet closes and the wardrobe refreshes).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnhanceItemSheet(
    item: ClothingItem,
    state: ImageSourceState,
    isSaving: Boolean,
    onStartWebMatch: () -> Unit,
    onStartAiRender: () -> Unit,
    onConfirmWebMatch: () -> Unit,
    onShowNextWebCandidate: () -> Unit,
    onRejectWebMatch: () -> Unit,
    onConfirmAiRender: () -> Unit,
    onRegenerateAiRender: () -> Unit,
    onTryAnotherMatch: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = VastraCream) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Create clean image", style = MaterialTheme.typography.headlineSmall, color = VastraInk)
            Text(
                "Choose how to create a clean image for " +
                    "\"${item.subCategory.ifEmpty { item.category.label }}\". " +
                    "The original photo is used only as a private reference.",
                style = MaterialTheme.typography.bodySmall,
                color = VastraMutedText
            )

            if (state is ImageSourceState.NotStarted) {
                // Two independent service buttons — SerpAPI and OpenAI are separate;
                // neither being configured should block the user from trying the other.
                Button(
                    onClick = onStartWebMatch,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VastraInk)
                ) {
                    Icon(Icons.Outlined.Search, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Find product match", color = VastraCream)
                }
                OutlinedButton(
                    onClick = onStartAiRender,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, VastraBorderColor),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VastraInk)
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Generate clean image")
                }
            } else {
                ImageSourceSection(
                    state = state,
                    onConfirmWebMatch = onConfirmWebMatch,
                    onShowNextWebCandidate = onShowNextWebCandidate,
                    onRejectWebMatch = onRejectWebMatch,
                    onConfirmAiRender = onConfirmAiRender,
                    onRegenerateAiRender = onRegenerateAiRender,
                    onTryAnotherMatch = onTryAnotherMatch
                )
            }

            if (isSaving) {
                LoadingRow("Saving clean image...")
            }

            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Close", color = VastraInk)
            }
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
    imageSourceStates: Map<Int, ImageSourceState>,
    isConfirming: Boolean,
    onToggle: (Int) -> Unit,
    onCategoryChange: (Int, ClothingCategory) -> Unit,
    onSubcategoryChange: (Int, String) -> Unit,
    onStartCleanImages: () -> Unit,
    onConfirmWebMatch: (Int) -> Unit,
    onShowNextWebCandidate: (Int) -> Unit,
    onRejectWebMatch: (Int) -> Unit,
    onConfirmAiRender: (Int) -> Unit,
    onRegenerateAiRender: (Int) -> Unit,
    onTryAnotherMatch: (Int) -> Unit,
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
                        aiSuggestion = item.subCategory,
                        imageSourceState = imageSourceStates[index] ?: ImageSourceState.NotStarted,
                        onToggle = { onToggle(index) },
                        onCategoryChange = { onCategoryChange(index, it) },
                        onSubcategoryChange = { onSubcategoryChange(index, it) },
                        onConfirmWebMatch = { onConfirmWebMatch(index) },
                        onShowNextWebCandidate = { onShowNextWebCandidate(index) },
                        onRejectWebMatch = { onRejectWebMatch(index) },
                        onConfirmAiRender = { onConfirmAiRender(index) },
                        onRegenerateAiRender = { onRegenerateAiRender(index) },
                        onTryAnotherMatch = { onTryAnotherMatch(index) }
                    )
                    if (index < detectedItems.lastIndex) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = VastraBorderColor)
                    }
                }
            }

            // ── Pinned actions ───────────────────────────────────────────────
            Spacer(Modifier.height(12.dp))

            val count = selectedIndices.size

            // Explicit, user-initiated clean-image action. NOTHING external runs
            // until this is tapped — and only for the selected/corrected items,
            // using their confirmed identity (e.g. joggers, not trousers).
            val anyNotStarted = selectedIndices.any {
                (imageSourceStates[it] ?: ImageSourceState.NotStarted) is ImageSourceState.NotStarted
            }
            if (anyNotStarted) {
                OutlinedButton(
                    onClick = onStartCleanImages,
                    enabled = count > 0 && !isConfirming,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, VastraInk)
                ) {
                    Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(18.dp), tint = VastraInk)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (count == 0) "Select items first"
                        else "Create clean wardrobe images",
                        style = MaterialTheme.typography.labelLarge,
                        color = VastraInk
                    )
                }
                Text(
                    "Searches the web / generates a clean image only for the items you select. No external calls happen until you tap this.",
                    style = MaterialTheme.typography.labelSmall,
                    color = VastraMutedText,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(10.dp))
            }

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
    aiSuggestion: String,
    imageSourceState: ImageSourceState,
    onToggle: () -> Unit,
    onCategoryChange: (ClothingCategory) -> Unit,
    onSubcategoryChange: (String) -> Unit,
    onConfirmWebMatch: () -> Unit,
    onShowNextWebCandidate: () -> Unit,
    onRejectWebMatch: () -> Unit,
    onConfirmAiRender: () -> Unit,
    onRegenerateAiRender: () -> Unit,
    onTryAnotherMatch: () -> Unit
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
            SubtypeField(
                category = category,
                value = subCategory,
                aiSuggestion = aiSuggestion,
                onValueChange = onSubcategoryChange,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(8.dp))

        ImageSourceSection(
            state = imageSourceState,
            onConfirmWebMatch = onConfirmWebMatch,
            onShowNextWebCandidate = onShowNextWebCandidate,
            onRejectWebMatch = onRejectWebMatch,
            onConfirmAiRender = onConfirmAiRender,
            onRegenerateAiRender = onRegenerateAiRender,
            onTryAnotherMatch = onTryAnotherMatch
        )
    }
}

/**
 * Clean-image sourcing flow for one detected garment. Shows the explicit
 * display-image state and the required user choices. The raw crop is NEVER
 * shown here as the final image — it is reference-only.
 *
 *   SearchingWeb            → spinner "Finding product match..."
 *   WebCandidatesAvailable  → candidate image + [Yes, use this] [Show alternatives]
 *                                                [None of these — generate clean image]
 *   WebConfirmed            → confirmed product image
 *   GeneratingAiRender      → spinner "Generating clean image..."
 *   AiRenderReadyForApproval→ render preview + [Use this clean image] [Regenerate] [Try another match]
 *   AiRenderConfirmed       → approved clean image
 *   DisplayImagePending     → placeholder "Clean wardrobe image not generated yet" + retry
 */
@Composable
private fun ImageSourceSection(
    state: ImageSourceState,
    onConfirmWebMatch: () -> Unit,
    onShowNextWebCandidate: () -> Unit,
    onRejectWebMatch: () -> Unit,
    onConfirmAiRender: () -> Unit,
    onRegenerateAiRender: () -> Unit,
    onTryAnotherMatch: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = VastraMuted,
        border = BorderStroke(1.dp, VastraBorderColor)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Wardrobe image", style = MaterialTheme.typography.labelSmall, color = VastraMutedText)

            when (state) {
                is ImageSourceState.NotStarted ->
                    Text(
                        "Pending · tap \"Create clean wardrobe images\" below to find a product match or generate a clean image.",
                        style = MaterialTheme.typography.labelSmall,
                        color = VastraMutedText
                    )

                is ImageSourceState.SearchingWeb ->
                    LoadingRow("Finding product match...")

                is ImageSourceState.WebCandidatesAvailable -> {
                    val c = state.current
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AsyncImage(
                            model = c.imageUrl,
                            contentDescription = c.title,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(VastraCard),
                            contentScale = ContentScale.Crop
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                c.title.take(48),
                                style = MaterialTheme.typography.bodySmall,
                                color = VastraInk, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            if (!c.siteName.isNullOrBlank()) {
                                Text(c.siteName, style = MaterialTheme.typography.labelSmall, color = VastraMutedText)
                            }
                            Text(
                                "Is this your item? (${state.shownIndex + 1}/${state.candidates.size})",
                                style = MaterialTheme.typography.labelSmall, color = VastraMutedText
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onConfirmWebMatch,
                            modifier = Modifier.weight(1f).height(36.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VastraInk)
                        ) { Text("Yes, use this", style = MaterialTheme.typography.labelSmall, color = VastraCream) }
                        if (state.hasMore) {
                            OutlinedButton(
                                onClick = onShowNextWebCandidate,
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, VastraBorderColor)
                            ) { Text("Show alternatives", style = MaterialTheme.typography.labelSmall, color = VastraInk) }
                        }
                    }
                    TextButton(
                        onClick = onRejectWebMatch,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) {
                        Text("None of these — generate clean image",
                            style = MaterialTheme.typography.labelSmall, color = VastraInk)
                    }
                }

                is ImageSourceState.WebConfirmed ->
                    ConfirmedRow(state.candidate.imageUrl, "Product image confirmed", state.candidate.siteName ?: "Web product match")

                is ImageSourceState.GeneratingAiRender ->
                    LoadingRow("Generating clean image...")

                is ImageSourceState.AiRenderReadyForApproval -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AsyncImage(
                            model = state.renderUrl,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(VastraCard),
                            contentScale = ContentScale.Crop
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Clean image ready", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = VastraInk)
                            Text("AI-generated · review before saving", style = MaterialTheme.typography.labelSmall, color = VastraMutedText)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onConfirmAiRender,
                            modifier = Modifier.weight(1f).height(36.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = VastraInk)
                        ) { Text("Use this clean image", style = MaterialTheme.typography.labelSmall, color = VastraCream) }
                        OutlinedButton(
                            onClick = onRegenerateAiRender,
                            modifier = Modifier.weight(1f).height(36.dp),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, VastraBorderColor)
                        ) { Text("Regenerate", style = MaterialTheme.typography.labelSmall, color = VastraInk) }
                    }
                    TextButton(
                        onClick = onTryAnotherMatch,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                    ) { Text("Try another match", style = MaterialTheme.typography.labelSmall, color = VastraInk) }
                }

                is ImageSourceState.AiRenderConfirmed ->
                    ConfirmedRow(state.renderUrl, "Clean image approved", "AI-generated product photo")

                is ImageSourceState.DisplayImagePending -> {
                    // Prominent banner so the user can clearly see WHY the state is pending
                    // (e.g. "SerpAPI not configured") rather than a silent return to buttons.
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFFF3E0)  // amber tint — visually distinct from the muted card
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.Info, null,
                                modifier = Modifier.size(16.dp).padding(top = 1.dp),
                                tint = Color(0xFFE65100)
                            )
                            Text(
                                state.reason,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF6D4C41)
                            )
                        }
                    }
                    if (state.canRetry) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onTryAnotherMatch,
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, VastraBorderColor)
                            ) { Text("Find web match", style = MaterialTheme.typography.labelSmall, color = VastraInk) }
                            OutlinedButton(
                                onClick = onRegenerateAiRender,
                                modifier = Modifier.weight(1f).height(36.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, VastraBorderColor)
                            ) { Text("Generate clean image", style = MaterialTheme.typography.labelSmall, color = VastraInk) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingRow(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = VastraMutedText)
        Text(text, style = MaterialTheme.typography.bodySmall, color = VastraMutedText)
    }
}

@Composable
private fun ConfirmedRow(imageUrl: String, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(VastraCard),
            contentScale = ContentScale.Crop
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.CheckCircle, null, modifier = Modifier.size(14.dp), tint = VastraInk)
                Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, color = VastraInk)
            }
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = VastraMutedText)
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

private const val CUSTOM_TYPE_LABEL = "Other / Custom"

/**
 * Type picker for the current category.
 *
 * Default mode is a plain single-tap menu that always lists every normalized
 * subtype for the category plus "Other / Custom" — the menu is NEVER filtered
 * by the current value, so changing trousers → joggers is one tap with no
 * backspacing. (The old version used `value` as a live search filter, which is
 * why erasing the AI text was needed and why backspace appeared to stick: each
 * keystroke re-filtered and re-narrowed the list, and recomposition kept
 * reapplying the field value.)
 *
 * Picking "Other / Custom" switches to a free-text field for anything outside
 * the vocabulary. Whatever value is set here at confirm time is what gets saved
 * as the canonical type.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubtypeField(
    category: ClothingCategory,
    value: String,
    aiSuggestion: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val allSuggestions = remember(category) { SubtypeVocabulary.suggestionsFor(category) }
    val matched = allSuggestions.firstOrNull { it.equals(value.trim(), ignoreCase = true) }
    // Custom mode when there is no vocabulary for this category, or the current
    // value is a non-empty value that isn't one of the menu options.
    val valueIsCustom = value.isNotBlank() && matched == null
    var customMode by remember(category) {
        mutableStateOf(allSuggestions.isEmpty() || valueIsCustom)
    }

    Column(modifier = modifier) {
        if (customMode && allSuggestions.isNotEmpty()) {
            // Free-text entry with a way back to the quick-pick list.
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                label = { Text("Type (custom)", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("e.g. culottes") },
                textStyle = MaterialTheme.typography.bodySmall,
                trailingIcon = {
                    IconButton(onClick = {
                        onValueChange("")
                        customMode = false
                    }) {
                        Icon(Icons.Filled.Close, contentDescription = "Back to list", tint = VastraBorderColor)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VastraInk,
                    unfocusedBorderColor = VastraBorderColor
                ),
                modifier = Modifier.fillMaxWidth()
            )
        } else if (allSuggestions.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it }
            ) {
                OutlinedTextField(
                    value = matched ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Type", style = MaterialTheme.typography.labelSmall) },
                    placeholder = { Text("Select type") },
                    textStyle = MaterialTheme.typography.bodySmall,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VastraInk,
                        unfocusedBorderColor = VastraBorderColor
                    ),
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    allSuggestions.forEach { suggestion ->
                        DropdownMenuItem(
                            text = { Text(suggestion, style = MaterialTheme.typography.bodySmall) },
                            onClick = {
                                onValueChange(suggestion)
                                expanded = false
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                CUSTOM_TYPE_LABEL,
                                style = MaterialTheme.typography.bodySmall,
                                color = VastraMutedText
                            )
                        },
                        onClick = {
                            onValueChange("")
                            customMode = true
                            expanded = false
                        }
                    )
                }
            }
        } else {
            // No vocabulary for this category — plain free-text.
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                label = { Text("Type", style = MaterialTheme.typography.labelSmall) },
                textStyle = MaterialTheme.typography.bodySmall,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VastraInk,
                    unfocusedBorderColor = VastraBorderColor
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Show the original AI guess once the user has changed it, so it's clear
        // the correction was applied (and what the model originally predicted).
        if (aiSuggestion.isNotBlank() && !aiSuggestion.equals(value.trim(), ignoreCase = true)) {
            Text(
                "AI suggested: $aiSuggestion",
                style = MaterialTheme.typography.labelSmall,
                color = VastraMutedText,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp)
            )
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
fun ClothingItemCard(item: ClothingItem, onDelete: () -> Unit, onEnhance: () -> Unit = {}) {
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
                    // Only show a clean confirmed display image (web product or AI
                    // render). For PENDING items show a placeholder — NEVER the raw
                    // crop, which is reference-only.
                    val hasCleanImage = !item.imageUrl.isNullOrBlank() &&
                        (item.displayImageSource == "WEB_PRODUCT" ||
                         item.displayImageSource == "AI_RENDER" ||
                         item.displayImageSource == "CROP")  // legacy rows only
                    if (hasCleanImage) {
                        AsyncImage(
                            model = item.imageUrl,
                            contentDescription = item.subCategory,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Column(
                            modifier = Modifier.fillMaxSize()
                                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                                .background(VastraMuted),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Icon(Icons.Outlined.Checkroom, null, modifier = Modifier.size(28.dp), tint = VastraBorderColor)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Clean image\nnot generated yet",
                                style = MaterialTheme.typography.labelSmall,
                                color = VastraMutedText,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onEnhance,
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, VastraBorderColor),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = VastraInk),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Create clean image", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
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
                    DropdownMenuItem(
                        text = { Text("Create clean image") },
                        onClick = { showMenu = false; onEnhance() }
                    )
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
