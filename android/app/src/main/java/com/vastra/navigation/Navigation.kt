package com.vastra.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Destination(val route: String) {
    object Auth : Destination("auth")
    object Login : Destination("login")
    object Register : Destination("register")
    object Feed : Destination("feed")
    object Wardrobe : Destination("wardrobe")
    object Discover : Destination("discover")
    object Saved : Destination("saved")
    object Profile : Destination("profile/{username}") {
        fun createRoute(username: String) = "profile/$username"
    }
    object PostDetail : Destination("post/{postId}") {
        fun createRoute(postId: String) = "post/$postId"
    }
    object RecreateStyle : Destination("recreate/{postId}") {
        fun createRoute(postId: String) = "recreate/$postId"
    }
}

data class BottomNavItem(
    val destination: Destination,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val label: String
)

val bottomNavItems = listOf(
    BottomNavItem(Destination.Feed, Icons.Filled.Home, Icons.Outlined.Home, "Feed"),
    BottomNavItem(Destination.Wardrobe, Icons.Filled.Checkroom, Icons.Outlined.Checkroom, "Wardrobe"),
    BottomNavItem(Destination.Discover, Icons.Filled.AutoAwesome, Icons.Outlined.AutoAwesome, "Discover"),
    BottomNavItem(Destination.Saved, Icons.Filled.Bookmark, Icons.Outlined.BookmarkBorder, "Saved"),
)
