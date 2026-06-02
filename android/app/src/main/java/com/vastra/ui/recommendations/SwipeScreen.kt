package com.vastra.ui.recommendations

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.vastra.data.model.ClothingItem
import com.vastra.data.model.SwipeDirection
import com.vastra.ui.theme.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun SwipeScreen(viewModel: SwipeViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VastraCream),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Discover",
                    style = MaterialTheme.typography.headlineMedium,
                    color = VastraCharcoal
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.FavoriteBorder, null, tint = SwipeLikeGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${uiState.likeCount}", style = MaterialTheme.typography.labelMedium, color = VastraSubtext)
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Filled.Close, null, tint = SwipeDislikeRed, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("${uiState.dislikeCount}", style = MaterialTheme.typography.labelMedium, color = VastraSubtext)
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    uiState.isLoading -> SwipeLoadingSkeleton()
                    uiState.cardStack.isEmpty() -> EmptySwipeState()
                    else -> {
                        uiState.cardStack.take(3).reversed().forEachIndexed { reversedIndex, item ->
                            val stackIndex = 2 - reversedIndex
                            SwipeCardItem(
                                item = item,
                                stackIndex = stackIndex,
                                isTopCard = stackIndex == 0,
                                onSwipe = { direction -> viewModel.onSwipe(item, direction) },
                                modifier = Modifier.zIndex(stackIndex.toFloat())
                            )
                        }
                    }
                }
            }

            if (uiState.cardStack.isNotEmpty()) {
                SwipeActionButtons(
                    onDislike = {
                        uiState.cardStack.firstOrNull()?.let { viewModel.onSwipe(it, SwipeDirection.DISLIKE) }
                    },
                    onLike = {
                        uiState.cardStack.firstOrNull()?.let { viewModel.onSwipe(it, SwipeDirection.LIKE) }
                    },
                    onSuperLike = {
                        uiState.cardStack.firstOrNull()?.let { viewModel.onSwipe(it, SwipeDirection.SUPER_LIKE) }
                    }
                )
                Spacer(Modifier.height(24.dp))
            }
        }

        uiState.error?.let { error ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
                action = { TextButton(onClick = { viewModel.clearError() }) { Text("Dismiss") } }
            ) { Text(error) }
        }
    }
}

@Composable
fun SwipeCardItem(
    item: ClothingItem,
    stackIndex: Int,
    isTopCard: Boolean,
    onSwipe: (SwipeDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val swipeThreshold = screenWidth * 0.4f

    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }

    var stampVisible by remember { mutableStateOf<SwipeDirection?>(null) }

    val rotation by remember(offsetX.value) {
        derivedStateOf { offsetX.value / 20f }
    }
    val likeAlpha by remember(offsetX.value) {
        derivedStateOf { (offsetX.value / swipeThreshold.value).coerceIn(0f, 1f) }
    }
    val nopeAlpha by remember(offsetX.value) {
        derivedStateOf { (-offsetX.value / swipeThreshold.value).coerceIn(0f, 1f) }
    }

    val scale = when (stackIndex) {
        0 -> 1f
        1 -> 0.95f
        else -> 0.90f
    }
    val verticalOffset = when (stackIndex) {
        0 -> 0.dp
        1 -> 12.dp
        else -> 24.dp
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = verticalOffset.toPx()
                translationX = if (isTopCard) offsetX.value else 0f
                rotationZ = if (isTopCard) rotation else 0f
            }
            .shadow(if (stackIndex == 0) 12.dp else 4.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .then(
                if (isTopCard) Modifier.pointerInput(item.id) {
                    coroutineScope {
                        detectDragGestures(
                            onDragEnd = {
                                val currentOffset = offsetX.value
                                launch {
                                    when {
                                        currentOffset > swipeThreshold.toPx() -> {
                                            offsetX.animateTo(
                                                screenWidth.toPx() * 1.5f,
                                                spring(Spring.StiffnessMediumLow, Spring.DampingRatioMediumBouncy)
                                            )
                                            onSwipe(SwipeDirection.LIKE)
                                            offsetX.snapTo(0f)
                                            offsetY.snapTo(0f)
                                        }
                                        currentOffset < -swipeThreshold.toPx() -> {
                                            offsetX.animateTo(
                                                -screenWidth.toPx() * 1.5f,
                                                spring(Spring.StiffnessMediumLow, Spring.DampingRatioMediumBouncy)
                                            )
                                            onSwipe(SwipeDirection.DISLIKE)
                                            offsetX.snapTo(0f)
                                            offsetY.snapTo(0f)
                                        }
                                        else -> {
                                            launch { offsetX.animateTo(0f, spring(Spring.StiffnessMediumLow, Spring.DampingRatioMediumBouncy)) }
                                            launch { offsetY.animateTo(0f, spring(Spring.StiffnessMediumLow, Spring.DampingRatioMediumBouncy)) }
                                        }
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                launch { offsetX.snapTo(offsetX.value + dragAmount.x) }
                                launch { offsetY.snapTo(offsetY.value + dragAmount.y * 0.3f) }
                            }
                        )
                    }
                } else Modifier
            )
    ) {
        AsyncImage(
            model = item.imageUrl,
            contentDescription = item.subCategory,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)),
                        startY = 300f
                    )
                )
        )

        if (isTopCard) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(24.dp)
                    .alpha(likeAlpha)
                    .border(3.dp, SwipeLikeGreen, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("LIKE", color = SwipeLikeGreen, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
                    .alpha(nopeAlpha)
                    .border(3.dp, SwipeDislikeRed, RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("NOPE", color = SwipeDislikeRed, fontWeight = FontWeight.Bold, fontSize = 24.sp)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(20.dp)
        ) {
            item.brand?.let { brand ->
                Text(brand, color = VastraGold, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Text(
                item.subCategory.ifEmpty { item.category.label },
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item.priceUsd?.let { price ->
                    Text("\$${"%.0f".format(price)}", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium)
                }
                // Style-match scoring is not implemented yet (the backend uses a
                // placeholder, not real type/occasion-aware logic), so we do NOT
                // show a "% match" badge — it would misrepresent fake numbers as
                // real Vastra intelligence. Re-enable once real scoring exists.
            }
            if (item.colorPalette.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    item.colorPalette.take(3).forEach { hex ->
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(parseHexColor(hex))
                                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SwipeActionButtons(onDislike: () -> Unit, onLike: () -> Unit, onSuperLike: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        FilledIconButton(
            onClick = onDislike,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White,
                contentColor = SwipeDislikeRed
            )
        ) {
            Icon(Icons.Filled.Close, "Dislike", modifier = Modifier.size(28.dp))
        }

        FilledIconButton(
            onClick = onSuperLike,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White,
                contentColor = SwipeSuperLikeBlue
            )
        ) {
            Icon(Icons.Filled.Star, "Super Like", modifier = Modifier.size(22.dp))
        }

        FilledIconButton(
            onClick = onLike,
            modifier = Modifier.size(56.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White,
                contentColor = SwipeLikeGreen
            )
        ) {
            Icon(Icons.Filled.Favorite, "Like", modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
fun SwipeLoadingSkeleton() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.7f)
            .clip(RoundedCornerShape(24.dp))
            .background(VastraSurfaceVariant)
    )
}

@Composable
fun EmptySwipeState() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(64.dp), tint = VastraGold)
        Spacer(Modifier.height(16.dp))
        Text("You've seen everything!", style = MaterialTheme.typography.titleMedium, color = VastraCharcoal)
        Text("Check back later for new styles", style = MaterialTheme.typography.bodyMedium, color = VastraSubtext)
    }
}

private fun parseHexColor(hex: String): Color = try {
    Color(android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex"))
} catch (e: Exception) { Color.Gray }
