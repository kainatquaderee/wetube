package dev.wetube.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.wetube.app.core.isValidVideoId
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.data.prefs.AppSettings
import dev.wetube.app.ui.auth.SignInScreen
import dev.wetube.app.ui.channel.ChannelScreen
import dev.wetube.app.ui.credits.CreditsScreen
import dev.wetube.app.ui.home.HomeScreen
import dev.wetube.app.ui.library.LibraryScreen
import dev.wetube.app.ui.player.PlayerScreen
import dev.wetube.app.ui.search.SearchScreen
import dev.wetube.app.ui.settings.SettingsScreen
import dev.wetube.app.ui.shorts.ShortsScreen
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("shorts", "Shorts", Icons.Default.PlayCircle),
    Tab("search", "Search", Icons.Default.Search),
    Tab("library", "Library", Icons.Default.VideoLibrary),
    Tab("settings", "Settings", Icons.Default.Settings),
)

@Composable
fun WeTubeRoot(
    locator: ServiceLocator,
    startVideoId: String? = null,
    startRequest: Int = 0,
    onStartConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }

    // "Keep playing in the background" can be switched off; in that case
    // leaving the app pauses audio instead of keeping the media session alive.
    val prefs by locator.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner, prefs.backgroundPlayback) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && !prefs.backgroundPlayback) {
                // While casting the local player is paused and silent, so pausing
                // only the phone would leave the TV playing. Going through the
                // router keeps the right side in charge in both modes.
                scope.launch {
                    if (locator.output.isRemote) locator.output.pause() else locator.playback.pause()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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
            composable("shorts") {
                ShortsScreen(
                    locator = locator,
                    onOpenChannel = { id -> navController.navigate("channel/$id") },
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
                    onChannelClick = { id -> navController.navigate("channel/$id") },
                )
            }
            composable("settings") {
                SettingsScreen(
                    locator = locator,
                    onSignIn = { navController.navigate("signin") },
                    onCredits = { navController.navigate("credits") },
                )
            }
            composable("credits") {
                CreditsScreen(onBack = { navController.popBackStack() })
            }
            composable("signin") {
                SignInScreen(
                    locator = locator,
                    onDone = { navController.popBackStack() },
                )
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
                    onOpenSettings = { navController.navigate("settings") },
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

    val consumeStart = rememberUpdatedState(onStartConsumed)

    // `startVideoId` is a one-shot delivery from the launch intent. The
    // activity clears it once this effect has navigated, so a recomposition
    // (or the effect restarting for any other reason) cannot replay the link.
    LaunchedEffect(startVideoId, startRequest) {
        val id = startVideoId ?: return@LaunchedEffect
        if (isValidVideoId(id)) {
            navController.navigate("watch/$id") { launchSingleTop = true }
        }
        consumeStart.value()
    }
}
