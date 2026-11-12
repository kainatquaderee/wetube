package dev.wetube.app.di

import android.content.Context
import dev.wetube.app.data.backend.BackendType
import dev.wetube.app.data.backend.InnerTubeBackend
import dev.wetube.app.data.backend.InvidiousBackend
import dev.wetube.app.data.backend.JsonHttp
import dev.wetube.app.data.backend.PipedBackend
import dev.wetube.app.data.backend.TubeBackend
import dev.wetube.app.data.extras.DislikeClient
import dev.wetube.app.data.extras.SponsorBlockClient
import dev.wetube.app.data.local.LibraryStore
import dev.wetube.app.data.prefs.AppSettings
import dev.wetube.app.data.prefs.SettingsStore
import dev.wetube.app.player.PlaybackController
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency container.
 *
 * Deliberately not a DI framework: the graph is small, explicit construction is
 * easier to follow, and it keeps the sample/build footprint low.
 */
class ServiceLocator(context: Context) {

    private val appContext = context.applicationContext

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonHttp = JsonHttp(http, json)

    val settings = SettingsStore(appContext)
    val library = LibraryStore(appContext, json)
    val sponsorBlock = SponsorBlockClient(jsonHttp)
    val dislikes = DislikeClient(jsonHttp)
    val playback = PlaybackController(appContext)

    @Volatile
    private var cached: Pair<AppSettings, TubeBackend>? = null

    suspend fun backend(): TubeBackend {
        val current = settings.settings.first()
        cached?.let { (cachedSettings, backend) ->
            if (cachedSettings == current) return backend
        }
        val backend = when (current.backend) {
            BackendType.INNERTUBE -> InnerTubeBackend(jsonHttp, current.region)
            BackendType.INVIDIOUS -> InvidiousBackend(jsonHttp, current.invidiousHost, current.proxyStreams)
            BackendType.PIPED -> PipedBackend(jsonHttp, current.pipedHost)
        }
        cached = current to backend
        return backend
    }
}
