package dev.wetube.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
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
import dev.wetube.app.ui.home.SubsScreen
import dev.wetube.app.ui.library.LibraryScreen
import dev.wetube.app.ui.library.PlaylistDetailScreen
import dev.wetube.app.ui.library.QueueScreen
import dev.wetube.app.ui.player.MiniPlayer
import dev.wetube.app.ui.player.PlayerScreen
import dev.wetube.app.ui.search.SearchScreen
import dev.wetube.app.ui.settings.SettingsScreen
import dev.wetube.app.ui.shorts.ShortsScreen
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home),
    Tab("shorts", "Shorts", Icons.Default.PlayCircle),
    // The aggregated uploads feed is its own tab, exactly like the real app:
    // Home is the recommended chip feed, subscriptions are a separate surface.
    Tab("subs", "Subs", Icons.Default.Subscriptions),
    Tab("search", "Search", Icons.Default.Search),
    Tab("library", "Library", Icons.Default.VideoLibrary),
    Tab("settings", "Settings", Icons.Default.Settings),
)

@Composable
fun WeTubeRoot(
    locator: ServiceLocator,
    startVideoId: String? = null,
    startRequest: Int = 0,
    inPictureInPicture: Boolean = false,
    onStartConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = tabs.any { it.route == currentRoute }

    // The video shrunk into a small in-app window when back leaves the player.
    // It lives here, above the navigation host, so the feed underneath stays
    // fully browsable while the video keeps playing. It is cleared whenever a
    // watch route is shown, so the full player and the window can never hold
    // the video at the same time.
    val controller by locator.playback.controller.collectAsStateWithLifecycle()
    var miniVideoId by remember { mutableStateOf<String?>(null) }
    // A clip tapped in Home's Shorts shelf, delivered to the Shorts section once.
    var shortsStart by remember { mutableStateOf<String?>(null) }
    val showMini = miniVideoId != null &&
        !inPictureInPicture &&
        currentRoute?.startsWith("watch") != true
    LaunchedEffect(currentRoute) {
        if (currentRoute?.startsWith("watch") == true) miniVideoId = null
    }

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

    Box(modifier = Modifier.fillMaxSize()) {
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
                        // A clip in Home's Shorts shelf opens the Shorts section
                        // on that clip; the request travels as state, not as a
                        // route argument, so the tab keeps its plain route and
                        // the bottom bar stays selected.
                        onShortsClick = { id ->
                            shortsStart = id
                            navController.navigate("shorts") { launchSingleTop = true }
                        },
                    )
                }
                composable("shorts") {
                    ShortsScreen(
                        locator = locator,
                        onOpenChannel = { id -> navController.navigate("channel/$id") },
                        startVideoId = shortsStart,
                        onStartConsumed = { shortsStart = null },
                    )
                }
                composable("subs") {
                    SubsScreen(
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
                        onChannelClick = { id -> navController.navigate("channel/$id") },
                        onPlaylistClick = { id -> navController.navigate("playlist/$id") },
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
                        inPictureInPicture = inPictureInPicture,
                        onBack = { navController.popBackStack() },
                        // Back while playing shrinks the video into the window
                        // above the feed instead of leaving the player: store
                        // the id and let the stack pop back to whatever screen
                        // the viewer came from.
                        onMinimize = { id ->
                            miniVideoId = id
                            navController.popBackStack()
                        },
                        onVideoClick = { id ->
                            navController.navigate("watch/$id") { launchSingleTop = true }
                        },
                        onChannelClick = { id -> navController.navigate("channel/$id") },
                        onOpenSettings = { navController.navigate("settings") },
                        onOpenQueue = { navController.navigate("queue") },
                    )
                }
                // Not one of the five top-level tabs, so `showBottomBar` stays
                // false here: a playlist is a detail of the library, not another
                // tab. Same reasoning as "queue" below.
                composable(
                    route = "playlist/{playlistId}",
                    arguments = listOf(navArgument("playlistId") { type = NavType.StringType }),
                ) { entry ->
                    PlaylistDetailScreen(
                        locator = locator,
                        playlistId = entry.arguments?.getString("playlistId").orEmpty(),
                        onVideoClick = { id ->
                            navController.navigate("watch/$id") { launchSingleTop = true }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                // Not one of the five top-level tabs, so `showBottomBar` stays
                // false here: the queue is a detail of playback, not another tab.
                composable("queue") {
                    QueueScreen(
                        locator = locator,
                        onVideoClick = { id -> navController.navigate("watch/$id") },
                        onBack = { navController.popBackStack() },
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

        if (showMini) {
            MiniPlayer(
                controller = controller,
                videoId = miniVideoId!!,
                audioOnly = prefs.audioOnly,
                // The queue can advance playback while the window is up, so
                // reopen whatever is actually in the player rather than the id
                // the window was created with, and fall back to it if the
                // session has no media id.
                onExpand = { id ->
                    if (miniVideoId != null) {
                        val live = controller?.currentMediaItem?.mediaId
                        val target = if (live != null && isValidVideoId(live)) live else id
                        miniVideoId = null
                        navController.navigate("watch/$target") { launchSingleTop = true }
                    }
                },
                onClose = {
                    locator.playback.pause()
                    miniVideoId = null
                },
                // Floats above the feed but clear of the bottom navigation bar.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 92.dp),
            )
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
