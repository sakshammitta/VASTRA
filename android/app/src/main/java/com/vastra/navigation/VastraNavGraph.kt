package com.vastra.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import com.vastra.ui.auth.LoginScreen
import com.vastra.ui.auth.RegisterScreen
import com.vastra.ui.feed.FeedScreen
import com.vastra.ui.profile.ProfileDrawerContent
import com.vastra.ui.recommendations.SwipeScreen
import com.vastra.ui.saved.SavedScreen
import com.vastra.ui.wardrobe.WardrobeScreen
import com.vastra.ui.theme.VastraCream
import com.vastra.ui.theme.VastraCharcoal
import com.vastra.ui.theme.VastraGold
import com.vastra.ui.theme.VastraOutline
import com.vastra.ui.theme.VastraSurface

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
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val navBackStack by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStack?.destination

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ProfileDrawerContent(
                onNavigateToLogin = onLogout,
                onClose = { scope.launch { drawerState.close() } }
            )
        }
    ) {
        Scaffold(
            containerColor = VastraCream,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = {},
                    actions = {
                        IconButton(onClick = {
                            scope.launch { drawerState.open() }
                        }) {
                            Icon(
                                Icons.Filled.AccountCircle,
                                contentDescription = "Profile",
                                modifier = Modifier.size(28.dp),
                                tint = VastraCharcoal
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = VastraCream)
                )
            },
            bottomBar = {
                NavigationBar(containerColor = VastraSurface, tonalElevation = 0.dp) {
                    bottomNavItems.forEach { item ->
                        val isSelected = currentDestination?.hierarchy?.any { it.route == item.destination.route } == true
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                navController.navigate(item.destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (isSelected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = VastraCharcoal,
                                selectedTextColor = VastraCharcoal,
                                indicatorColor = VastraCream,
                                unselectedIconColor = VastraOutline,
                                unselectedTextColor = VastraOutline
                            )
                        )
                    }
                }
            }
        ) { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = Destination.Feed.route,
                modifier = Modifier.padding(paddingValues)
            ) {
                composable(Destination.Feed.route) { FeedScreen() }
                composable(Destination.Wardrobe.route) { WardrobeScreen() }
                composable(Destination.Discover.route) { SwipeScreen() }
                composable(Destination.Saved.route) { SavedScreen() }
            }
        }
    }
}
