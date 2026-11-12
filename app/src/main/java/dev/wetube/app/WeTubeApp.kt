package dev.wetube.app

import android.app.Application
import dev.wetube.app.di.ServiceLocator

class WeTubeApp : Application() {

    lateinit var locator: ServiceLocator
        private set

    override fun onCreate() {
        super.onCreate()
        locator = ServiceLocator(this)
    }
}
