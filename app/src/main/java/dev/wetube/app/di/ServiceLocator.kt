package dev.wetube.app.di

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.wetube.app.BuildConfig
import dev.wetube.app.data.backend.BackendType
import dev.wetube.app.data.backend.ContentFilterBackend
import dev.wetube.app.data.backend.InnerTubeBackend
import dev.wetube.app.data.backend.InvidiousBackend
import dev.wetube.app.data.backend.JsonHttp
import dev.wetube.app.data.backend.PipedBackend
import dev.wetube.app.data.backend.RemotePlaylist
import dev.wetube.app.data.backend.SyncBackend
import dev.wetube.app.data.backend.TubeBackend
import dev.wetube.app.data.auth.AuthStore
import dev.wetube.app.data.extras.DislikeClient
import dev.wetube.app.data.extras.SponsorBlockClient
import dev.wetube.app.data.content.AgeGateStore
import dev.wetube.app.data.download.DownloadManager
import dev.wetube.app.data.download.DownloadService
import dev.wetube.app.data.download.DownloadState
import dev.wetube.app.data.download.DownloadStore
import dev.wetube.app.data.download.FileDownloader
import dev.wetube.app.data.download.downloadLimitBytes
import dev.wetube.app.data.download.mediaDirectory
import dev.wetube.app.data.local.BackupStore
import dev.wetube.app.data.local.AccountKeySource
import dev.wetube.app.data.local.LibraryStore
import dev.wetube.app.data.local.SyncMetadataStore
import dev.wetube.app.data.prefs.AppSettings
import dev.wetube.app.data.prefs.SettingsStore
import dev.wetube.app.player.CaptionLoader
import dev.wetube.app.player.CastSessionKeeper
import dev.wetube.app.player.OutputRouter
import dev.wetube.app.player.PlaybackController
import dev.wetube.app.player.remote.NetworkRemoteDiscovery
import dev.wetube.app.player.remote.RemotePlaybackManager
import dev.wetube.app.sync.DEFAULT_FAVOURITES_TITLE
import dev.wetube.app.sync.PlaylistTarget
import dev.wetube.app.sync.SyncDeps
import dev.wetube.app.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.Inet6Address
import java.net.InetAddress
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

    /**
     * Some Wi-Fi networks publish AAAA records but silently drop IPv6. Android
     * hands those addresses back first, and OkHttp tries them one at a time, so
     * every request can burn its timeouts before IPv4 is attempted. We sort
     * IPv4 first and keep IPv6 as the fallback, which is also what IPv6-only
     * networks need.
     */
    private val ipv4First = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> =
            Dns.SYSTEM.lookup(hostname).sortedBy { it is Inet6Address }
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .dns(ipv4First)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .apply {
            if (BuildConfig.DEBUG) HttpLog.install(this)
        }
        .build()

    private val jsonHttp = JsonHttp(http, json)

    val auth = AuthStore(appContext, jsonHttp)

    val settings = SettingsStore(appContext)
    /**
     * One metadata store over the same `ktube_library` DataStore [library]
     * uses; both resolve the one `Context.libraryStore` delegate, so they
     * share a single DataStore instance on the file.
     */
    val sync = SyncMetadataStore(appContext, json)
    val library = LibraryStore(
        appContext,
        json,
        object : AccountKeySource {
            override suspend fun current(): String? = auth.accountKey.first()
        },
    )
    val backup = BackupStore(library, settings, json)

    /**
     * The download queue's own record, over the same `ktube_library` DataStore
     * [library] uses; both resolve the one `Context.libraryStore` delegate.
     */
    val downloadStore = DownloadStore(appContext, json)

    /**
     * The UI's handle on offline downloads and the sink the download service
     * reports progress to. Lazy, like the rest of the service-backed graph: a
     * user who never downloads anything never builds the downloader or wakes the
     * service.
     */
    val downloads: DownloadManager by lazy {
        DownloadManager(
            store = downloadStore,
            downloader = FileDownloader(http),
            directory = mediaDirectory(appContext),
            resolveSource = { id -> backend().progressive(id) },
            startWorker = {
                // A foreground service start can be refused when the app is in
                // the background; a failed nudge is not worth crashing over,
                // because the queue is re-drained the next time the app is in
                // front.
                runCatching {
                    ContextCompat.startForegroundService(
                        appContext,
                        Intent(appContext, DownloadService::class.java),
                    )
                }
            },
            scope = appScope,
            // The stored limit is in the settings' 1000-based megabytes — the
            // same base the dropdown labels ("1 GB") and the UI's byte formatter
            // use — so the ceiling enforced on disk is the figure the user sees.
            limitBytes = { downloadLimitBytes(settings.settings.first().downloadLimitMb) },
        )
    }

    /**
     * Picks up whatever offline work a previous process left queued.
     *
     * A download the user asked for is recorded before the service starts, but
     * the service is `START_NOT_STICKY`: a process killed mid-transfer leaves a
     * record the store reads back as `QUEUED`, and nothing ever starts the
     * service to drain it until the user downloads something else. Reading the
     * queue once at launch and waking the worker only when it holds queued work
     * fixes that without building the manager or the service for a user who
     * never downloads.
     */
    fun resumePendingDownloads() {
        appScope.launch {
            if (downloadStore.downloads.first().any { it.state == DownloadState.QUEUED }) {
                downloads.start()
            }
        }
    }

    val ageGate = AgeGateStore(appContext)
    val sponsorBlock = SponsorBlockClient(jsonHttp)
    val dislikes = DislikeClient(jsonHttp)
    val playback = PlaybackController(appContext)
    val captionLoader = CaptionLoader(jsonHttp)

    /**
     * Process-scoped coroutines for work that must outlive a screen — the cast
     * poll loop. Created on first use, like the rest of the casting graph.
     */
    private val appScope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    /**
     * Where playback is: phone or a LAN renderer.
     *
     * Deliberately lazy, all the way down: a user who never opens the device
     * sheet never constructs the SSDP socket, the DLNA transport, the poll
     * scope or a discovery sweep. Everything a cast needs is built on the first
     * touch of the cast UI, and the local player is unaffected until then.
     */
    val output: OutputRouter by lazy {
        val remote = RemotePlaybackManager(
            discovery = NetworkRemoteDiscovery(appContext),
            resolveProgressive = { id -> backend().progressive(id) },
            scope = appScope,
        )
        // The poll loop lives in the process, not in a screen, so the process
        // has to stay alive — and awake — while a TV owns playback. The keeper
        // is bound to the remote manager's session, not to the router's output,
        // so a mid-session failure also releases the keep-alive.
        CastSessionKeeper(appContext, remote.state, appScope).start()
        OutputRouter(local = playback, remote = remote)
    }

    /** Settings plus whichever account the backends were built for. */
    private data class BackendKey(val settings: AppSettings, val account: String)

    @Volatile
    private var cached: Pair<BackendKey, TubeBackend>? = null

    suspend fun backend(): TubeBackend {
        val current = settings.settings.first()
        // Rebuild when the session changes: a signed-in backend sends cookies,
        // a signed-out one must not keep using them.
        val account = auth.account.first()?.cookies.orEmpty()
        val key = BackendKey(current, account)
        cached?.let { (cachedKey, backend) ->
            if (cachedKey == key) return backend
        }
        val backend = ContentFilterBackend(
            when (current.backend) {
                BackendType.INNERTUBE -> InnerTubeBackend(jsonHttp, current.region, auth)
                BackendType.INVIDIOUS -> InvidiousBackend(jsonHttp, current.invidiousHost, current.proxyStreams)
                BackendType.PIPED -> PipedBackend(jsonHttp, current.pipedHost)
            },
            settings,
            ageGate,
        )
        cached = key to backend
        return backend
    }

    /**
     * The sync engine, wired to the live account, settings and transport.
     *
     * Lazy, and built only over *readers* of the rest of the graph ([auth],
     * [settings], [backend]), so a user who never signs in never constructs a
     * transport-backed engine. [SyncDeps] is an anonymous object rather than a
     * named type because each method is a one-line adapter over an existing flow
     * and there is exactly one production wiring; a test drives the engine with
     * its own deps instead.
     */
    val syncEngine: SyncEngine by lazy {
        SyncEngine(
            library = library,
            metadata = sync,
            deps = object : SyncDeps {
                // `this@ServiceLocator` is required: the anonymous object's own
                // `backend()` would otherwise shadow and recurse into itself.
                override suspend fun backend(): SyncBackend? = this@ServiceLocator.backend().sync

                override suspend fun accountKey(): String? = auth.accountKey.first()

                override suspend fun syncEnabled(): Boolean =
                    settings.settings.first().syncEnabled

                override suspend fun accountLabel(): String =
                    auth.account.first()?.displayName.orEmpty()

                override suspend fun mirrorRemovals(): Boolean =
                    settings.settings.first().mirrorRemovals

                /**
                 * Favourites start on a playlist WeTube will create on the first
                 * push. Reuse of an existing mirror is driven by the engine from
                 * the persisted `AccountSyncState.favouritesPlaylistId`, so a
                 * playlist is never adopted because of its title — a user's own
                 * "Favourites" playlist can never be repurposed.
                 */
                override suspend fun favouriteTarget(remote: List<RemotePlaylist>): PlaylistTarget =
                    PlaylistTarget.CreateOnFirstPush(DEFAULT_FAVOURITES_TITLE)

                override suspend fun firstSyncDone(accountKey: String): Boolean =
                    sync.state(accountKey).firstSyncDone
            },
            scope = appScope,
        )
    }
}
