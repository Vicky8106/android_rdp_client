package com.freerdp.client.session

import android.content.Context
import com.freerdp.feature.telemetry.reconnect.AutoReconnectManager
import com.freerdp.feature.telemetry.reconnect.NetworkStateMonitor

/**
 * Adapter over the feature-telemetry [NetworkStateMonitor] so the session layer can start
 * and stop connectivity monitoring without depending on the Android ConnectivityManager directly.
 */
interface NetworkMonitor {
    fun start()
    fun stop()
    val isActive: Boolean
}

class AndroidNetworkMonitor(
    context: Context,
    reconnectManager: AutoReconnectManager
) : NetworkMonitor {

    private val delegate = NetworkStateMonitor(context.applicationContext, reconnectManager)

    override fun start() = delegate.startMonitoring()

    override fun stop() = delegate.stopMonitoring()

    override val isActive: Boolean get() = delegate.isMonitoring
}
