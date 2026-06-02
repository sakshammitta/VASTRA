package com.vastra.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.vastra.ui.auth.LoginScreen
import com.vastra.ui.auth.RegisterScreen
import com.vastra.ui.feed.FeedScreen
import com.vastra.ui.profile.MeScreen
import com.vastra.ui.scan.ScanScreen
import com.vastra.ui.wardrobe.WardrobeScreen
import com.vastra.ui.theme.VastraCream
import com.vastra.ui.theme.VastraInk
import com.vastra.ui.theme.VastraCard
import com.vastra.ui.theme.VastraBorderColor
import com.vastra.ui.theme.VastraMutedText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VastraNavGraph(isLoggedIn: Boolean) {
    val rootNavController = rememberNavController()

    NavHost(
        navController = rootNavController,
        startDestination = if (isLoggedIn) "main" else Destination.Login.route
    ) {
        composable(Destination.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    rootNavController.navigate("main") {
                        popUpTo(Destination.Login.route) { inclusive = true }
                    }
                },
                onNavigateToRegister = { rootNavController.navigate(Destination.Register.route) }
            )
        }
        composable(Destination.Register.route) {
            RegisterScreen(
                onRegisterSuccess = {
                    rootNavController.navigate("main") {
                        popUpTo(Destination.Register.route) { inclusive = true }
                    }
                },
                onNavigateToLogin = { rootNavController.popBackStack() }
            )
        }
        composable("main") {
            MainScreen(onLogout = {
                rootNavController.navigate(Destination.Login.route) {
                    popUpTo("main") { inclusive = true }
                }
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onLogout: () -> Unit) {
    val navController = rememberNavController()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStack?.destination

    // Hide the bottom bar on the full-screen Scan flow.
    val showBottomBar = currentDestination?.route != Destination.Scan.route

    Scaffold(
        containerColor = VastraCream,
        bottomBar = {
            if (showBottomBar) {
                VastraBottomBar(
                    currentRoute = currentDestination?.route,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onScan = {
                        navController.navigate(Destination.Scan.route) {
                            launchSingleTop = true
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Destination.Feed.route,
            modifier = Modifier.padding(paddingValues)
        ) {
            composable(Destination.Feed.route) { FeedScreen() }
            composable(Destination.Wardrobe.route) {
                WardrobeScreen(
                    onNavigateToScan = {
                        navController.navigate(Destination.Scan.route) { launchSingleTop = true }
                    }
                )
            }
            composable(Destination.Me.route) {
                MeScreen(onLogout = onLogout)
            }
            composable(Destination.Scan.route) {
                ScanScreen(
                    onBack = { navController.popBackStack() },
                    onFinishToWardrobe = {
                        // After confirm/save, land the user on Wardrobe to see the new item.
                        navController.navigate(Destination.Wardrobe.route) {
                            popUpTo(Destination.Scan.route) { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                    // On HTTP 401 the JWT is invalid/expired — send the user to login;
                    // a fresh login overwrites the stored token.
                    onSessionExpired = onLogout
                )
            }
        }
    }
}

/**
 * Glass pill bottom bar (Lovable): Feed | Wardrobe | raised ink Scan button | Me.
 */
@Composable
private fun VastraBottomBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onScan: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 20.dp, top = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(32.dp),
            color = VastraCard,
            border = androidx.compose.foundation.BorderStroke(1.dp, VastraBorderColor),
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavTab(
                    item = bottomNavItems[0], // Feed
                    isSelected = currentRoute == bottomNavItems[0].destination.route,
                    onClick = { onNavigate(bottomNavItems[0].destination.route) },
                    modifier = Modifier.weight(1f)
                )
                NavTab(
                    item = bottomNavItems[1], // Wardrobe
                    isSelected = currentRoute == bottomNavItems[1].destination.route,
                    onClick = { onNavigate(bottomNavItems[1].destination.route) },
                    modifier = Modifier.weight(1f)
                )

                // Central raised Scan button (special primary action)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .wrapContentWidth(Alignment.CenterHorizontally)
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(CircleShape)
                            .background(VastraInk)
                            .clickable(onClick = onScan),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = "Scan item",
                            tint = VastraCream,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                NavTab(
                    item = bottomNavItems[2], // Me
                    isSelected = currentRoute == bottomNavItems[2].destination.route,
                    onClick = { onNavigate(bottomNavItems[2].destination.route) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun NavTab(
    item: BottomNavItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (isSelected) VastraInk else VastraMutedText
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            if (isSelected) item.selectedIcon else item.unselectedIcon,
            contentDescription = item.label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            item.label,
            color = tint,
            fontSize = 10.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
        )
    }
}
