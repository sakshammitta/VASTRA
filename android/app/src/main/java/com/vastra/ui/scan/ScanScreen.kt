package com.vastra.ui.scan

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vastra.ui.theme.*
import com.vastra.ui.wardrobe.WardrobeViewModel
import com.vastra.ui.wardrobe.toTempFile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onBack: () -> Unit,
    onFinishToWardrobe: () -> Unit = onBack,
    onSessionExpired: () -> Unit = {},
    viewModel: WardrobeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        // Stay on screen — the scanning overlay appears, then the selection sheet,
        // and navigation happens only after confirmation/dismissal below.
        if (uri == null) {
            android.util.Log.d("VastraScan", "gallery picker returned null Uri (user cancelled)")
        } else {
            android.util.Log.d("VastraScan", "gallery picker returned uri=$uri")
            viewModel.reportScanStatus("Image selected", "uri=$uri")
            runCatching { uri.toTempFile(context) }
                .onSuccess { viewModel.scanImage(it) }
                .onFailure { e ->
                    android.util.Log.e("VastraScan", "toTempFile failed", e)
                    viewModel.reportScanError("Couldn't read the selected image: ${e.message}")
                }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VastraCream)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {

            // ── Top bar ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, "Back", tint = VastraInk)
                }
            }

            // ── Step label + heading ────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(top = 4.dp, bottom = 20.dp)
            ) {
                Text(
                    "STEP 1 OF 2",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                    color = VastraMutedText
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Scan closet",
                    style = MaterialTheme.typography.headlineLarge,
                    color = VastraInk
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Take or upload a photo of a clothing item to add it to your digital wardrobe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VastraMutedText
                )
            }

            // ── Large framed upload area ────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(VastraCard)
                    .border(
                        width = 1.5.dp,
                        color = VastraBorderColor,
                        shape = RoundedCornerShape(20.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                // Corner frame marks (Lovable prototype)
                CornerFrameMarks()

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Outlined.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = VastraMutedText
                    )
                    // "FRAME ONE PIECE" indicator
                    Surface(
                        color = VastraSand,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            "FRAME ONE PIECE",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                            color = VastraMutedText
                        )
                    }
                    Text(
                        "Lay flat or hang the item\nagainst a plain background",
                        style = MaterialTheme.typography.bodySmall,
                        color = VastraMutedText,
                        textAlign = TextAlign.Center
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Action buttons ──────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Gallery (connected to working flow)
                Button(
                    onClick = { galleryLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = VastraInk)
                ) {
                    Icon(Icons.Outlined.PhotoLibrary, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Choose from Gallery", style = MaterialTheme.typography.labelLarge)
                }

                // Camera (disabled, coming soon)
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, VastraBorderColor),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VastraMutedText)
                ) {
                    Icon(Icons.Outlined.CameraAlt, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Use Camera", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(8.dp))
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

            // ── Scan-flow debug panel (debug builds only) ───────────────────
            // Hidden in release builds; shows live status + error text on the
            // phone so testing doesn't depend on Logcat.
            if (com.vastra.BuildConfig.DEBUG && (uiState.scanStatus != null || uiState.error != null)) {
                Spacer(Modifier.height(16.dp))
                val isError = uiState.error != null
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    border = BorderStroke(2.dp, if (isError) Color(0xFFD32F2F) else VastraInk)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "SCAN DEBUG",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                            color = if (isError) Color(0xFFD32F2F) else VastraInk,
                            fontWeight = FontWeight.Bold
                        )
                        uiState.scanStatus?.let {
                            Text("Status: $it", style = MaterialTheme.typography.bodyMedium, color = Color.Black)
                        }
                        uiState.scanDebug?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
                        }
                        if (isError) {
                            Text(
                                uiState.error!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFD32F2F)
                            )
                            if (uiState.sessionExpired) {
                                Button(
                                    onClick = { viewModel.clearError(); onSessionExpired() },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = VastraInk)
                                ) {
                                    Text("Sign in again", color = VastraCream, fontWeight = FontWeight.SemiBold)
                                }
                            } else {
                                TextButton(onClick = { viewModel.clearError() }) {
                                    Text("Dismiss", color = Color(0xFFD32F2F), fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Quick Tips panel ────────────────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(16.dp),
                color = VastraCard,
                border = BorderStroke(1.dp, VastraBorderColor)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "QUICK TIPS",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.5.sp),
                        color = VastraMutedText
                    )
                    QuickTip(icon = Icons.Outlined.WbSunny, text = "Use natural lighting for best results")
                    QuickTip(icon = Icons.Outlined.Straighten, text = "Lay flat or hang against a plain wall")
                    QuickTip(icon = Icons.Outlined.CropFree, text = "Frame one item at a time")
                    QuickTip(icon = Icons.Outlined.HighQuality, text = "Higher resolution = better detection")
                }
            }

            Spacer(Modifier.height(32.dp))
        }

        // ── Scanning indicator overlay ───────────────────────────────────────
        if (uiState.activeScanJobs.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = VastraCard
                ) {
                    Column(
                        modifier = Modifier.padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = VastraInk, strokeWidth = 3.dp)
                        Text("Scanning your item…", style = MaterialTheme.typography.bodyMedium, color = VastraInk)
                    }
                }
            }
        }

        // ── Detected-items selection sheet ──────────────────────────────────
        // Shown after scan completes; navigates back to Wardrobe after confirm/dismiss.
        uiState.pendingConfirmJob?.let { job ->
            com.vastra.ui.wardrobe.ScanResultSelectionSheet(
                detectedItems = job.detectedItems,
                selectedIndices = uiState.selectedDetectedIndices,
                editedCategories = uiState.editedCategories,
                editedSubcategories = uiState.editedSubcategories,
                isConfirming = uiState.isConfirming,
                onToggle = { viewModel.toggleDetectedItem(it) },
                onCategoryChange = { i, c -> viewModel.setItemCategory(i, c) },
                onSubcategoryChange = { i, s -> viewModel.setItemSubcategory(i, s) },
                onConfirm = {
                    viewModel.confirmSelectedItems()
                    onFinishToWardrobe()
                },
                onDismiss = {
                    viewModel.dismissScanResult()
                    onBack()
                }
            )
        }

        // Errors are now shown in the always-visible SCAN DEBUG panel above
        // (inline below the Gallery button), not a snackbar — the snackbar
        // rendered blank on-device and was easy to miss.
    }
}

@Composable
private fun CornerFrameMarks() {
    val color = VastraBorderColor
    val size = 24.dp
    val thickness = 2.dp
    Box(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Top-left
        Box(modifier = Modifier.align(Alignment.TopStart)) {
            Box(Modifier.width(size).height(thickness).background(color))
            Box(Modifier.width(thickness).height(size).background(color))
        }
        // Top-right
        Box(modifier = Modifier.align(Alignment.TopEnd)) {
            Box(Modifier.width(size).height(thickness).background(color).align(Alignment.TopEnd))
            Box(Modifier.width(thickness).height(size).background(color).align(Alignment.TopEnd))
        }
        // Bottom-left
        Box(modifier = Modifier.align(Alignment.BottomStart)) {
            Box(Modifier.width(thickness).height(size).background(color).align(Alignment.BottomStart))
            Box(Modifier.width(size).height(thickness).background(color).align(Alignment.BottomStart))
        }
        // Bottom-right
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            Box(Modifier.width(size).height(thickness).background(color).align(Alignment.BottomEnd))
            Box(Modifier.width(thickness).height(size).background(color).align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun QuickTip(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, modifier = Modifier.size(16.dp), tint = VastraMutedText)
        Text(text, style = MaterialTheme.typography.bodySmall, color = VastraMutedText)
    }
}
