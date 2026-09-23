package com.freerdp.client

import android.app.Application
import com.freerdp.client.di.AppContainer

/**
 * Application entry point. The [AppContainer] is created once per process — this is
 * what makes the profile list survive process recreation paths and the session survive
 * configuration changes.
 */
class App : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(appContext = this)
    }
}
