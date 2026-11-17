package dev.wetube.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dev.wetube.app.di.ServiceLocator
import dev.wetube.app.ui.player.GeckoEngine

class WeTubeApp : Application(), ImageLoaderFactory {

    lateinit var locator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        // Built here, not from the player screen: the process lifecycle is still
        // INITIALIZED in onCreate, and creating the runtime any later replays a
        // resume into Gecko before its handler exists. See GeckoEngine.warmUp.
        //
        // Only the default process may do it. Application.onCreate runs once per
        // process, and Gecko spawns a content process per tab plus a crash helper;
        // a runtime built inside one of those is fatal, and the crash helper
        // refuses outright ("Crash handler service must run in a separate
        // process").
        if (Application.getProcessName() == packageName) {
            GeckoEngine.warmUp(this)
        }
        locator = ServiceLocator(this)
        // Work left queued by a process that stopped is picked up here: the
        // download service is not restarted by the platform, so without this a
        // record stranded mid-queue would wait for the user's next download.
        locator.resumePendingDownloads()
        // Content written before history and playlists became account-scoped sits
        // under bare keys. Claim it for the account signed in on this first
        // launch so the upgrade does not look like a wipe.
        locator.adoptLegacyContent()
    }

    /**
     * Coil normally builds its own OkHttp client, which means it also inherits
     * the platform's IPv6-first address order. Sharing [ServiceLocator.http]
     * keeps image requests on the same IPv4-first resolver as the API calls —
     * without this, thumbnails time out on networks with broken IPv6.
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient { locator.http }
            .crossfade(true)
            .build()
}
