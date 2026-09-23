package com.freerdp.feature.telemetry

import android.content.Context
import com.freerdp.core.engine.MockRdpEngine
import com.freerdp.core.engine.RdpConnectionConfig
import com.freerdp.feature.telemetry.reconnect.AutoReconnectManager
import com.freerdp.feature.telemetry.reconnect.AutoReconnectManagerImpl
import com.freerdp.feature.telemetry.reconnect.NetworkStateMonitor
import com.freerdp.feature.telemetry.reconnect.ReconnectState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NetworkStateMonitor contract tests (F20 network callbacks): registration
 * idempotency, rapid flapping safety, and driving the AutoReconnectManager.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NetworkStateMonitorTest {

    private fun contextWithoutConnectivity(): Context {
        val context = mockk<Context>()
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns null
        return context
    }

    @Test
    fun testStartStopIsIdempotentWithoutConnectivityManager() {
        val manager = mockk<AutoReconnectManager>(relaxed = true)
        val monitor = NetworkStateMonitor(contextWithoutConnectivity(), manager)

        assertFalse(monitor.isMonitoring)

        monitor.startMonitoring()
        assertTrue(monitor.isMonitoring)
        monitor.startMonitoring() // second start: no-op, no throw
        assertTrue(monitor.isMonitoring)

        monitor.stopMonitoring()
        assertFalse(monitor.isMonitoring)
        monitor.stopMonitoring() // double stop: no-op, no throw
        assertFalse(monitor.isMonitoring)
    }

    @Test
    fun testStopBeforeStartIsSafe() {
        val manager = mockk<AutoReconnectManager>(relaxed = true)
        val monitor = NetworkStateMonitor(contextWithoutConnectivity(), manager)

        monitor.stopMonitoring() // never registered
        assertFalse(monitor.isMonitoring)

        monitor.startMonitoring()
        assertTrue(monitor.isMonitoring)
    }

    @Test
    fun testRapidRegistrationFlappingConvergesToStopped() {
        val manager = mockk<AutoReconnectManager>(relaxed = true)
        val monitor = NetworkStateMonitor(contextWithoutConnectivity(), manager)

        repeat(10) {
            monitor.startMonitoring()
            assertTrue(monitor.isMonitoring)
            monitor.stopMonitoring()
            assertFalse(monitor.isMonitoring)
        }
        assertFalse(monitor.isMonitoring)
    }

    @Test
    fun testNetworkSignalsDriveReconnectManagerEndToEnd() = runTest {
        val engine = MockRdpEngine()
        val manager = AutoReconnectManagerImpl(
            engine = engine,
            configProvider = { RdpConnectionConfig(serverAddress = "10.0.0.5", port = 3389) },
            coroutineScope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )
        val monitor = NetworkStateMonitor(contextWithoutConnectivity(), manager)
        monitor.startMonitoring()

        // Network lost => teardown into WaitingForNetwork.
        monitor.dispatchNetworkLost()
        advanceUntilIdle()
        assertTrue(
            "onLost callback must transition the manager to WaitingForNetwork",
            manager.reconnectState.value is ReconnectState.WaitingForNetwork
        )

        // Network available => fast-path reconnect to Connected.
        monitor.dispatchNetworkAvailable()
        advanceUntilIdle()
        assertTrue(
            "onAvailable callback must fast-path the manager to Connected",
            manager.reconnectState.value is ReconnectState.Connected
        )

        monitor.stopMonitoring()
        assertFalse(monitor.isMonitoring)
    }

    @Test
    fun testRepeatedFlappingSignalsConvergeToConnected() = runTest {
        val engine = MockRdpEngine()
        val manager = AutoReconnectManagerImpl(
            engine = engine,
            configProvider = { RdpConnectionConfig(serverAddress = "10.0.0.5", port = 3389) },
            coroutineScope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )
        val monitor = NetworkStateMonitor(contextWithoutConnectivity(), manager)

        repeat(5) {
            monitor.dispatchNetworkLost()
            advanceUntilIdle()
            assertTrue(manager.reconnectState.value is ReconnectState.WaitingForNetwork)
            monitor.dispatchNetworkAvailable()
            advanceUntilIdle()
            assertTrue(manager.reconnectState.value is ReconnectState.Connected)
        }
        assertFalse(manager.reconnectState.value is ReconnectState.Failed)
    }

    @Test
    fun testSignalsAfterManagerShutdownAreIgnored() = runTest {
        val engine = MockRdpEngine()
        val manager = AutoReconnectManagerImpl(
            engine = engine,
            configProvider = { RdpConnectionConfig(serverAddress = "10.0.0.5", port = 3389) },
            coroutineScope = this,
            ioDispatcher = StandardTestDispatcher(testScheduler)
        )
        manager.shutdown()

        val monitor = NetworkStateMonitor(contextWithoutConnectivity(), manager)
        monitor.dispatchNetworkLost()
        monitor.dispatchNetworkAvailable()
        advanceUntilIdle()

        assertTrue(
            "Post-shutdown network signals must not move the state machine",
            manager.reconnectState.value is ReconnectState.Idle
        )
    }
}
