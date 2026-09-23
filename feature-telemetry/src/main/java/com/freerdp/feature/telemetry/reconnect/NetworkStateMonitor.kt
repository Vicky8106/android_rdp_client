package com.freerdp.feature.telemetry.reconnect

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android system network callback listener that drives [AutoReconnectManager].
 */
class NetworkStateMonitor(
    private val context: Context,
    private val reconnectManager: AutoReconnectManager
) {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val isRegistered = AtomicBoolean(false)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            dispatchNetworkAvailable()
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            dispatchNetworkLost()
        }
    }

    /**
     * Network-available signal routed to the [AutoReconnectManager] fast-path recovery.
     * Exposed (internal) so the callback contract is unit-testable without Android's
     * ConnectivityManager plumbing; the ConnectivityManager callback above delegates here.
     */
    internal fun dispatchNetworkAvailable() {
        reconnectManager.onNetworkAvailable()
    }

    /**
     * Network-loss signal routed to the [AutoReconnectManager] teardown/wait path.
     */
    internal fun dispatchNetworkLost() {
        reconnectManager.onNetworkLost()
    }

    /**
     * Registers the network callback with the Android ConnectivityManager.
     * Idempotent: a second [startMonitoring] while registered is a no-op.
     * When no ConnectivityManager is available (non-standard / JVM test environments)
     * registration degrades to a state-flag no-op instead of crashing.
     */
    fun startMonitoring() {
        if (isRegistered.compareAndSet(false, true)) {
            val connectivity = connectivityManager ?: return
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivity.registerNetworkCallback(request, networkCallback)
            } catch (e: Exception) {
                // Graceful fallback for non-standard environments
            }
        }
    }

    /**
     * Unregisters the network callback. Idempotent: stop-before-start or double-stop
     * are safe no-ops.
     */
    fun stopMonitoring() {
        if (isRegistered.compareAndSet(true, false)) {
            try {
                connectivityManager?.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                // Ignore if already unregistered
            }
        }
    }

    val isMonitoring: Boolean get() = isRegistered.get()
}
