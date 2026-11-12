package dev.wetube.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import dev.wetube.app.ui.WeTubeRoot
import dev.wetube.app.ui.theme.WeTubeTheme

class MainActivity : ComponentActivity() {

    private val startVideoId = mutableStateOf<String?>(null)

    /** Bumped for every launch intent so the same link can be opened twice. */
    private val startRequest = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val locator = (application as WeTubeApp).locator
        locator.playback.connect()

        requestNotificationPermission()
        open(intent)

        setContent {
            WeTubeTheme {
                WeTubeRoot(
                    locator = locator,
                    startVideoId = startVideoId.value,
                    startRequest = startRequest.intValue,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        open(intent)
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

    /** youtube.com/watch?v=ID, youtu.be/ID */
    private fun videoIdFrom(intent: Intent?): String? {
        val data = intent?.data ?: return null
        val host = data.host.orEmpty()
        return when {
            host.contains("youtu.be") -> data.pathSegments.firstOrNull()
            data.getQueryParameter("v") != null -> data.getQueryParameter("v")
            else -> null
        }
    }
}
