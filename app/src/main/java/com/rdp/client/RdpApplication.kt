package com.rdp.client

import android.app.Application
import android.util.Log

/**
 * Base Application class for the RDP Client.
 * Provides global singleton access, lifecycle hooks, and centralized initialization.
 */
class RdpApplication : Application() {

    companion object {
        private const val TAG = "RdpApplication"

        @Volatile
        private var instance: RdpApplication? = null

        /**
         * Returns the global application instance.
         */
        fun getInstance(): RdpApplication {
            return instance ?: throw IllegalStateException("RdpApplication not yet initialized")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "RdpApplication initialized successfully. Package: ${packageName}")
    }
}
