package dev.wetube.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dev.wetube.app.di.ServiceLocator

class WeTubeApp : Application(), ImageLoaderFactory {

    lateinit var locator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        locator = ServiceLocator(this)
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
