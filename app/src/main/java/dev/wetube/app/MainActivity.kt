package dev.wetube.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.wetube.app.core.isValidVideoId
import dev.wetube.app.ui.WeTubeRoot
import dev.wetube.app.ui.theme.WeTubeTheme

class MainActivity : ComponentActivity() {

    private val startVideoId = mutableStateOf<String?>(null)

    /** Bumped for every launch intent so the same link can be opened twice. */
    private val startRequest = mutableIntStateOf(0)

    /**
     * Whether the window is currently shrunk to a picture-in-picture box.
     *
     * Compose cannot read this from the window itself, so the activity mirrors
     * it into snapshot state and the player uses it to drop all chrome and show
     * the video alone. It is also the flag the player reads to decide that the
     * back gesture has already been spent.
     */
    private val inPictureInPicture = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keep the display on for as long as the app is in the foreground. This
        // is the cheapest fix for the screen sleeping under a viewer: the flag
        // lives on the window, so Android drops it the moment the window is not
        // visible and it can never hold the screen on from behind another app.
        // The player still does its own finer-grained hold for the surfaces it
        // owns; this simply covers every screen, including the feeds.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val locator = (application as WeTubeApp).locator
        locator.playback.connect()

        requestNotificationPermission()
        // Only a cold launch carries a new link. On recreation Android replays
        // the original intent, which would otherwise reopen the same video
        // every time the activity is restored (rotation, process death).
        if (savedInstanceState == null) open(intent)

        setContent {
            WeTubeTheme {
                WeTubeRoot(
                    locator = locator,
                    startVideoId = startVideoId.value,
                    startRequest = startRequest.intValue,
                    inPictureInPicture = inPictureInPicture.value,
                    onStartConsumed = { startVideoId.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture.value = isInPictureInPictureMode
    }

    private fun open(intent: Intent?) {
        startVideoId.value = videoIdFrom(intent)
        startRequest.intValue++
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                100,
            )
        }
    }

    /**
     * youtube.com/watch?v=ID, youtu.be/ID.
     *
     * The host list is exact — `contains("youtu.be")` also matched
     * `evil-youtu.be.example` — and the id itself must be a YouTube-shaped id,
     * because it is concatenated into a navigation route.
     */
    private fun videoIdFrom(intent: Intent?): String? {
        val data = intent?.data ?: return null
        // Normalised the same way as the paste path (VideoIds.youtubeVideoId):
        // strip `www.` and accept the bare `youtube.com` host, so a tapped link
        // and a pasted one can never diverge.
        val host = data.host?.lowercase()?.removePrefix("www.") ?: return null
        val candidate = when (host) {
            "youtu.be" -> data.pathSegments.firstOrNull()
            "youtube.com", "m.youtube.com" -> data.getQueryParameter("v")
            else -> null
        }
        return candidate?.takeIf(::isValidVideoId)
    }
}
