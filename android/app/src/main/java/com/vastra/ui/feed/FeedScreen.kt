package com.vastra.ui.feed

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.vastra.data.model.Post
import com.vastra.data.model.ShoppableTag
import com.vastra.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(viewModel: FeedViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    LaunchedEffect(listState.firstVisibleItemIndex) {
        val totalItems = uiState.posts.size
        if (listState.firstVisibleItemIndex >= totalItems - 3 && uiState.hasMore && !uiState.isLoadingMore) {
            viewModel.loadMore()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(VastraCream)) {
        if (uiState.isLoading) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(5) { FeedPostSkeleton() }
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("VASTRA", style = MaterialTheme.typography.headlineMedium, color = VastraCharcoal, fontWeight = FontWeight.Bold)
                        // Notifications not implemented yet — shown disabled so it isn't misleading.
                        IconButton(onClick = {}, enabled = false) {
                            Icon(Icons.Outlined.Notifications, "Notifications (coming soon)", tint = VastraSubtext.copy(alpha = 0.4f))
                        }
                    }
                }
                items(uiState.posts, key = { it.id }) { post ->
                    PostCard(
                        post = post,
                        onLike = { viewModel.likePost(post.id) },
                        onSave = { viewModel.savePost(post.id) },
                        onComment = { viewModel.openComments(post.id) },
                        onRecreate = { viewModel.recreateStyle(post.id) },
                        isRecreating = uiState.recreatingPostId == post.id
                    )
                }
                if (uiState.isLoadingMore) {
                    item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = VastraGold) } }
                }
            }
        }

        uiState.activeCommentPostId?.let { postId ->
            CommentBottomSheet(
                comments = uiState.comments,
                isLoading = uiState.isLoadingComments,
                onDismiss = { viewModel.closeComments() },
                onAddComment = { text -> viewModel.addComment(postId, text) }
            )
        }

        uiState.recreateResult?.let { result ->
            RecreateStyleDialog(
                result = result,
                onDismiss = { viewModel.dismissRecreateResult() }
            )
        }
    }
}

@Composable
fun PostCard(
    post: Post,
    onLike: () -> Unit,
    onSave: () -> Unit,
    onComment: () -> Unit,
    onRecreate: () -> Unit,
    isRecreating: Boolean
) {
    var showTags by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 0.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = VastraSurface),
        shape = RoundedCornerShape(0.dp),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = post.author.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(VastraSurfaceVariant),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(post.author.displayName, style = MaterialTheme.typography.titleSmall, color = VastraCharcoal)
                    if (post.styleMetadata.styleLabels.isNotEmpty()) {
                        Text(post.styleMetadata.styleLabels.first(), style = MaterialTheme.typography.labelSmall, color = VastraSubtext)
                    }
                }
                // Follow / social graph not wired yet — disabled so it isn't misleading.
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    border = BorderStroke(1.dp, VastraOutline),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("Follow", color = VastraSubtext.copy(alpha = 0.5f), style = MaterialTheme.typography.labelMedium)
                }
            }

            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(4f / 5f).clickable { showTags = !showTags }
            ) {
                AsyncImage(
                    model = post.imageUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                if (showTags && post.shoppableTags.isNotEmpty()) {
                    ShoppableTagOverlay(tags = post.shoppableTags)
                }
                if (post.shoppableTags.isNotEmpty()) {
                    Box(
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                            .padding(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.ShoppingBag, null, tint = Color.White, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("${post.shoppableTags.size}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onLike, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (post.isLikedByMe) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        "Like",
                        tint = if (post.isLikedByMe) SwipeLikeGreen else VastraCharcoal
                    )
                }
                if (post.likeCount > 0) Text("${post.likeCount}", style = MaterialTheme.typography.labelMedium, color = VastraSubtext)

                IconButton(onClick = onComment, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.ChatBubbleOutline, "Comment", tint = VastraCharcoal)
                }
                if (post.commentCount > 0) Text("${post.commentCount}", style = MaterialTheme.typography.labelMedium, color = VastraSubtext)

                Spacer(Modifier.weight(1f))

                TextButton(onClick = onRecreate, enabled = !isRecreating) {
                    if (isRecreating) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = VastraGold, strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("Recreate", color = VastraGold, style = MaterialTheme.typography.labelMedium)
                }

                IconButton(onClick = onSave, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (post.isSavedByMe) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                        "Save",
                        tint = if (post.isSavedByMe) VastraGold else VastraCharcoal
                    )
                }
            }

            post.caption?.let { caption ->
                Text(
                    buildString {
                        append(post.author.username)
                        append("  ")
                        append(caption)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = VastraCharcoal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider(color = VastraOutline, thickness = 0.5.dp)
        }
    }
}

@Composable
fun ShoppableTagOverlay(tags: List<ShoppableTag>) {
    var tappedTag by remember { mutableStateOf<ShoppableTag?>(null) }
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 8f, targetValue = 14f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize().clickable { tappedTag = null }) {
            tags.forEach { tag ->
                val x = tag.xNorm * size.width
                val y = tag.yNorm * size.height
                drawCircle(color = Color.White.copy(alpha = 0.3f), radius = pulseRadius + 4, center = Offset(x, y))
                drawCircle(color = Color.White, radius = 8f, center = Offset(x, y))
                drawCircle(color = Color(0xFF1A1A1A), radius = 4f, center = Offset(x, y))
            }
        }

        tags.forEach { tag ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable { tappedTag = if (tappedTag == tag) null else tag }
            )
            tappedTag?.let { active ->
                if (active == tag) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(tag.xNorm)
                            .fillMaxHeight(tag.yNorm)
                            .wrapContentSize(Alignment.BottomEnd)
                            .padding(4.dp),
                        color = Color.Black.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            tag.label,
                            modifier = Modifier.padding(8.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentBottomSheet(
    comments: List<com.vastra.data.model.Comment>,
    isLoading: Boolean,
    onDismiss: () -> Unit,
    onAddComment: (String) -> Unit
) {
    var commentText by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = VastraSurface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("Comments", style = MaterialTheme.typography.titleMedium, color = VastraCharcoal, modifier = Modifier.padding(bottom = 16.dp))
            if (isLoading) {
                Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = VastraGold)
                }
            } else {
                comments.take(20).forEach { comment ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        AsyncImage(
                            model = comment.author.avatarUrl,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp).clip(CircleShape).background(VastraSurfaceVariant),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(comment.author.username, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = VastraCharcoal)
                            Text(comment.text, style = MaterialTheme.typography.bodySmall, color = VastraCharcoal)
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    placeholder = { Text("Add a comment…", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    maxLines = 1
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (commentText.isNotBlank()) {
                            onAddComment(commentText.trim())
                            commentText = ""
                        }
                    }
                ) {
                    Icon(Icons.Filled.Send, "Post", tint = VastraGold)
                }
            }
        }
    }
}

@Composable
fun RecreateStyleDialog(result: com.vastra.data.model.RecreateStyleResponse, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VastraSurface,
        title = { Text("Style Recreated", style = MaterialTheme.typography.headlineSmall) },
        text = {
            Column {
                // Style matching is not live yet — the backend match score is a
                // placeholder, so we mark this clearly instead of showing a fake %.
                Text("Preview · style matching is not live yet", style = MaterialTheme.typography.bodyMedium, color = VastraSubtext, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(12.dp))
                if (result.ownedMatches.isNotEmpty()) {
                    Text("You already own (${result.ownedMatches.size} items):", style = MaterialTheme.typography.labelMedium, color = VastraSubtext)
                    result.ownedMatches.take(3).forEach { item ->
                        Text("• ${item.subCategory.ifEmpty { item.category.label }}", style = MaterialTheme.typography.bodySmall, color = VastraCharcoal)
                    }
                }
                if (result.gapItems.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("To complete the look (${result.gapItems.size} items):", style = MaterialTheme.typography.labelMedium, color = VastraSubtext)
                    result.gapItems.take(3).forEach { item ->
                        Text("• ${item.subCategory.ifEmpty { item.category.label }} ${item.priceUsd?.let { "~\$${"%.0f".format(it)}" } ?: ""}", style = MaterialTheme.typography.bodySmall, color = VastraCharcoal)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it", color = VastraGold) } }
    )
}

@Composable
fun FeedPostSkeleton() {
    val shimmer = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by shimmer.animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "shimmer"
    )
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(VastraSurfaceVariant.copy(alpha = shimmerAlpha)))
            Spacer(Modifier.width(12.dp))
            Column {
                Box(Modifier.width(120.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(VastraSurfaceVariant.copy(alpha = shimmerAlpha)))
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(80.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(VastraSurfaceVariant.copy(alpha = shimmerAlpha)))
            }
        }
        Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f).background(VastraSurfaceVariant.copy(alpha = shimmerAlpha)))
    }
}
