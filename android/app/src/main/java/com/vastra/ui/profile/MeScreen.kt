package com.vastra.ui.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.vastra.ui.theme.*

/**
 * Me / profile page (Lovable structure). Real values are wired where available
 * (item count); features without backend support (style summary, friends,
 * looks, weather, activity, goals) are shown as neutral / coming-soon states.
 */
@Composable
fun MeScreen(
    onLogout: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val user = uiState.user

    Box(modifier = Modifier.fillMaxSize().background(VastraCream)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            // ── Header ──────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        "YOUR STYLE",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp),
                        color = VastraMutedText
                    )
                    Text("Me", style = MaterialTheme.typography.headlineLarge, color = VastraInk)
                }
                IconButton(onClick = {}) {
                    // Settings — placeholder until a settings screen exists.
                    Icon(Icons.Outlined.Settings, "Settings", tint = VastraInk)
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Profile identity ────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                AsyncImage(
                    model = user?.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(VastraMuted),
                    contentScale = ContentScale.Crop
                )
                Column {
                    Text(
                        user?.displayName ?: "—",
                        style = MaterialTheme.typography.titleLarge,
                        color = VastraInk,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        user?.let { "@${it.username}" } ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VastraMutedText
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Stats row (items real; friends/looks placeholder) ───────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MeStatCard("Items", user?.itemCount?.toString() ?: "—", Modifier.weight(1f))
                MeStatCard("Friends", "—", Modifier.weight(1f))
                MeStatCard("Looks", "—", Modifier.weight(1f))
            }

            Spacer(Modifier.height(16.dp))

            // ── Style Summary (dark, coming soon) ───────────────────────────
            ComingSoonDarkCard(
                icon = Icons.Outlined.AutoAwesome,
                title = "Style Summary",
                body = "Your personalized style breakdown will appear here once we've analyzed more of your wardrobe."
            )

            Spacer(Modifier.height(12.dp))

            // ── Today / weather (coming soon) ───────────────────────────────
            ComingSoonCard(
                icon = Icons.Outlined.WbSunny,
                title = "Today",
                body = "Weather-based outfit suggestions coming soon."
            )

            Spacer(Modifier.height(12.dp))

            // ── Style Profile (coming soon) ─────────────────────────────────
            ComingSoonCard(
                icon = Icons.Outlined.Palette,
                title = "Style Profile",
                body = "Your color palette and style preferences will be built from your closet."
            )

            Spacer(Modifier.height(12.dp))

            // ── Monthly activity (coming soon) ──────────────────────────────
            ComingSoonCard(
                icon = Icons.Outlined.BarChart,
                title = "This Month",
                body = "Activity tracking coming soon."
            )

            Spacer(Modifier.height(12.dp))

            // ── Current Goals (coming soon) ─────────────────────────────────
            ComingSoonCard(
                icon = Icons.Outlined.Flag,
                title = "Current Goals",
                body = "Set and track style goals — coming soon."
            )

            Spacer(Modifier.height(20.dp))

            // ── Log out ─────────────────────────────────────────────────────
            OutlinedButton(
                onClick = {
                    viewModel.logout()
                    onLogout()
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, VastraBorderColor),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = VastraError)
            ) {
                Icon(Icons.Outlined.Logout, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Log Out", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun MeStatCard(label: String, value: String, modifier: Modifier = Modifier) {
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
            Text(value, style = MaterialTheme.typography.headlineSmall, color = VastraInk, textAlign = TextAlign.Center)
            Text(label, style = MaterialTheme.typography.labelSmall, color = VastraMutedText, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ComingSoonCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = VastraCard,
        border = BorderStroke(1.dp, VastraBorderColor)
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = VastraMutedText, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = VastraInk)
                    Surface(color = VastraSand, shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Soon",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = VastraMutedText
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = VastraMutedText)
            }
        }
    }
}

@Composable
private fun ComingSoonDarkCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = VastraInk
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, tint = VastraCream.copy(alpha = 0.6f), modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.labelMedium, color = VastraCream)
                    Surface(color = Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
                        Text(
                            "Soon",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = VastraCream.copy(alpha = 0.7f)
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = VastraCream.copy(alpha = 0.5f))
            }
        }
    }
}
