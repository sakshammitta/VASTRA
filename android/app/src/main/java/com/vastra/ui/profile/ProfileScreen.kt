package com.vastra.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.vastra.core.storage.TokenStore
import com.vastra.data.model.User
import com.vastra.data.repository.ApiResult
import com.vastra.data.repository.AuthRepository
import com.vastra.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val user: User? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepo: AuthRepository,
    private val tokenStore: TokenStore,
    private val vastraApiService: com.vastra.data.remote.VastraApiService
) : ViewModel() {
    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    init {
        tokenStore.getUsername()?.let { loadProfile(it) }
    }

    fun loadProfile(username: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val response = vastraApiService.getProfile(username)
                if (response.isSuccessful) {
                    _uiState.update { it.copy(user = response.body(), isLoading = false) }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun logout() {
        authRepo.logout()
    }
}

@Composable
fun ProfileDrawerContent(
    onNavigateToLogin: () -> Unit,
    onClose: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(300.dp)
            .background(VastraSurface)
            .padding(vertical = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Profile", style = MaterialTheme.typography.titleLarge, color = VastraCharcoal)
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, "Close", tint = VastraCharcoal)
            }
        }

        HorizontalDivider(color = VastraOutline, modifier = Modifier.padding(vertical = 8.dp))

        uiState.user?.let { user ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = user.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(CircleShape).background(VastraSurfaceVariant),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(12.dp))
                Text(user.displayName, style = MaterialTheme.typography.titleMedium, color = VastraCharcoal, fontWeight = FontWeight.Bold)
                Text("@${user.username}", style = MaterialTheme.typography.bodySmall, color = VastraSubtext)
                user.bio?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = VastraSubtext, modifier = Modifier.padding(top = 4.dp)) }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.SpaceEvenly, modifier = Modifier.fillMaxWidth()) {
                    StatItem(label = "Posts", value = user.postCount.toString())
                    StatItem(label = "Items", value = user.itemCount.toString())
                    StatItem(label = "Following", value = user.followingCount.toString())
                }
            }
        } ?: run {
            if (uiState.isLoading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = VastraGold)
                }
            }
        }

        HorizontalDivider(color = VastraOutline, modifier = Modifier.padding(vertical = 8.dp))

        val menuItems = listOf(
            Triple(Icons.Outlined.Person, "Edit Profile", {}),
            Triple(Icons.Outlined.ShoppingBag, "Purchase History", {}),
            Triple(Icons.Outlined.Tune, "Style Preferences", {}),
            Triple(Icons.Outlined.Settings, "Settings", {}),
        )

        menuItems.forEach { (icon, label, action) ->
            DrawerMenuItem(icon = icon, label = label, onClick = action)
        }

        Spacer(Modifier.weight(1f))

        HorizontalDivider(color = VastraOutline, modifier = Modifier.padding(vertical = 8.dp))

        DrawerMenuItem(
            icon = Icons.Outlined.Logout,
            label = "Log Out",
            onClick = {
                viewModel.logout()
                onNavigateToLogin()
            },
            tint = VastraError
        )
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = VastraCharcoal, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = VastraSubtext)
    }
}

@Composable
fun DrawerMenuItem(icon: ImageVector, label: String, onClick: () -> Unit, tint: androidx.compose.ui.graphics.Color = VastraCharcoal) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}
