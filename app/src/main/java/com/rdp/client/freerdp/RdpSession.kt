package com.rdp.client.freerdp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Stateful controller managing an active RDP session lifecycle.
 */
class RdpSession(
    private val context: Context?,
    val parameters: RdpConnectionParameters,
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : RdpSessionListener {

    private val TAG = "RdpSession"
    private val stateMachine = RdpSessionStateMachine(RdpSessionState.DISCONNECTED)
    private val lifecycleMutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Native instance pointer returned by LibFreeRDP.newInstance() */
    var instanceId: Long = 0L
        private set

    /** Reactive StateFlow observing session state transitions */
    val state: StateFlow<RdpSessionState> = stateMachine.stateFlow

    /** Stream of dirty rectangles emitted on graphics updates */
    private val _dirtyRectFlow = MutableSharedFlow<Rect>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val dirtyRectFlow: SharedFlow<Rect> = _dirtyRectFlow.asSharedFlow()

    /** Last reported error message or code */
    var lastErrorMessage: String? = null
        private set

    /**
     * Initiates connection sequence asynchronously on externalScope (Dispatchers.IO).
     */
    fun connect() {
        val scope = if (externalScope.isActive) externalScope else cleanupScope
        scope.launch {
            lifecycleMutex.withLock {
                if (stateMachine.currentState == RdpSessionState.DISCONNECTING) return@withLock
                if (!stateMachine.transitionTo(RdpSessionState.CONNECTING).isSuccess) {
                    Log.w(TAG, "Cannot initiate connect from current state: ${stateMachine.currentState}")
                    return@withLock
                }

                // 1. Validate connection parameters
                val validation = parameters.validate()
                if (!validation.isSuccess) {
                    lastErrorMessage = (validation as ConnectionValidationResult.Error).message
                    stateMachine.transitionTo(RdpSessionState.ERROR)
                    return@withLock
                }

                // 2. Allocate native instance
                try {
                    instanceId = LibFreeRDP.newInstance(context)
                    if (instanceId == 0L) {
                        lastErrorMessage = "Failed to allocate native FreeRDP instance"
                        stateMachine.transitionTo(RdpSessionState.ERROR)
                        return@withLock
                    }

                    // Register listener for reverse JNI callbacks
                    LibFreeRDP.registerSessionListener(instanceId, this@RdpSession)

                    // 3. Invoke native connect
                    val connectSuccess = LibFreeRDP.connect(instanceId, parameters)
                    if (!connectSuccess) {
                        lastErrorMessage = LibFreeRDP.getLastError(instanceId) ?: "Failed to initiate native connect"
                        stateMachine.transitionTo(RdpSessionState.ERROR)
                        cleanupNative()
                        return@withLock
                    }

                    Log.i(TAG, "Native connect initiated successfully for instance: $instanceId")
                } catch (e: Throwable) {
                    Log.e(TAG, "Exception during session connection: ${e.message}", e)
                    lastErrorMessage = e.message
                    stateMachine.transitionTo(RdpSessionState.ERROR)
                    cleanupNative()
                }
            }
        }
    }

    /**
     * Initiates graceful disconnection.
     */
    fun disconnect() {
        val scope = if (externalScope.isActive) externalScope else cleanupScope
        scope.launch {
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    if (stateMachine.currentState.isTerminal && instanceId == 0L) return@withLock
                    stateMachine.transitionTo(RdpSessionState.DISCONNECTING)

                    if (instanceId != 0L) {
                        try {
                            LibFreeRDP.disconnect(instanceId)
                        } catch (e: Throwable) {
                            Log.e(TAG, "Exception during disconnect: ${e.message}", e)
                        } finally {
                            cleanupNative()
                        }
                    }
                    stateMachine.transitionTo(RdpSessionState.DISCONNECTED)
                }
            }
        }
    }

    private suspend fun cleanupNative() {
        withContext(NonCancellable) {
            val inst = instanceId
            if (inst != 0L) {
                instanceId = 0L
                LibFreeRDP.unregisterSessionListener(inst)
                LibFreeRDP.freeInstance(inst)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Input Event Dispatchers
    // -------------------------------------------------------------------------

    fun sendCursorEvent(x: Int, y: Int, flags: Int): Boolean {
        if (instanceId == 0L || stateMachine.currentState != RdpSessionState.CONNECTED) return false
        return LibFreeRDP.sendCursorEvent(instanceId, x, y, flags)
    }

    fun sendKeyEvent(scancode: Int, extended: Boolean, down: Boolean): Boolean {
        if (instanceId == 0L || stateMachine.currentState != RdpSessionState.CONNECTED) return false
        return LibFreeRDP.sendKeyEvent(instanceId, scancode, extended, down)
    }

    fun sendUnicodeKeyEvent(codePoint: Int): Boolean {
        if (instanceId == 0L || stateMachine.currentState != RdpSessionState.CONNECTED) return false
        return LibFreeRDP.sendUnicodeKeyEvent(instanceId, codePoint)
    }

    fun updateGraphics(bitmap: Bitmap, rect: Rect): Boolean {
        if (instanceId == 0L) return false
        return LibFreeRDP.updateGraphics(instanceId, bitmap, rect.left, rect.top, rect.width(), rect.height())
    }

    // -------------------------------------------------------------------------
    // RdpSessionListener Callbacks
    // -------------------------------------------------------------------------

    override fun onConnectionSuccess(instance: Long) {
        stateMachine.transitionTo(RdpSessionState.CONNECTED)
    }

    override fun onConnectionFailure(instance: Long, errorCode: Int, message: String) {
        lastErrorMessage = "Connection failed [$errorCode]: $message"
        stateMachine.transitionTo(RdpSessionState.ERROR)
        cleanupScope.launch {
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    cleanupNative()
                }
            }
        }
    }

    override fun onDisconnected(instance: Long) {
        stateMachine.transitionTo(RdpSessionState.DISCONNECTED)
        cleanupScope.launch {
            withContext(NonCancellable) {
                lifecycleMutex.withLock {
                    cleanupNative()
                }
            }
        }
    }

    override fun onGraphicsUpdate(instance: Long, x: Int, y: Int, width: Int, height: Int) {
        _dirtyRectFlow.tryEmit(Rect(x, y, x + width, y + height))
    }

    override fun onGraphicsResize(instance: Long, width: Int, height: Int, bpp: Int) {
        Log.i(TAG, "Desktop resized to ${width}x${height} @ ${bpp}bpp")
    }
}
