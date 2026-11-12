package dev.wetube.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.channel.ChannelScreen
import dev.wetube.app.ui.home.HomeScreen
import dev.wetube.app.ui.library.LibraryScreen
import dev.wetube.app.ui.player.PlayerScreen
import dev.wetube.app.ui.search.SearchScreen
import dev.wetube.app.ui.settings.SettingsScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("search", "Search", Icons.Default.Search),
    Tab("library", "Library", Icons.Default.VideoLibrary),
    Tab("settings", "Settings", Icons.Default.Settings),
)

@Composable
fun WeTubeRoot(locator: ServiceLocator, startVideoId: String? = null) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                if (currentRoute != tab.route) {
                                    navController.navigate(tab.route) {
                                        popUpTo("home") { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
        ) {
            composable("home") {
                HomeScreen(
                    locator = locator,
                    onVideoClick = { id -> navController.navigate("watch/$id") },
                    onChannelClick = { id -> navController.navigate("channel/$id") },
                )
            }
            composable("search") {
                SearchScreen(
                    locator = locator,
                    onVideoClick = { id -> navController.navigate("watch/$id") },
                    onChannelClick = { id -> navController.navigate("channel/$id") },
                )
            }
            composable("library") {
                LibraryScreen(
                    locator = locator,
                    onVideoClick = { id -> navController.navigate("watch/$id") },
                )
            }
            composable("settings") {
                SettingsScreen(locator = locator)
            }
            composable(
                route = "watch/{videoId}",
                arguments = listOf(navArgument("videoId") { type = NavType.StringType }),
            ) { entry ->
                PlayerScreen(
                    locator = locator,
                    videoId = entry.arguments?.getString("videoId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onVideoClick = { id ->
                        navController.navigate("watch/$id") { launchSingleTop = true }
                    },
                    onChannelClick = { id -> navController.navigate("channel/$id") },
                )
            }
            composable(
                route = "channel/{channelId}",
                arguments = listOf(navArgument("channelId") { type = NavType.StringType }),
            ) { entry ->
                ChannelScreen(
                    locator = locator,
                    channelId = entry.arguments?.getString("channelId").orEmpty(),
                    onBack = { navController.popBackStack() },
                    onVideoClick = { id ->
                        navController.navigate("watch/$id") { launchSingleTop = true }
                    },
                )
            }
        }
    }

    LaunchedEffect(startVideoId) {
        if (!startVideoId.isNullOrBlank()) {
            navController.navigate("watch/$startVideoId") { launchSingleTop = true }
        }
    }
}
