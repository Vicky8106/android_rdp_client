package com.freerdp.core.engine

import kotlinx.coroutines.flow.StateFlow

interface IRdpEngine {
    val connectionState: StateFlow<RdpConnectionState>
    val sessionMetrics: StateFlow<RdpSessionMetrics>

    suspend fun connect(config: RdpConnectionConfig): Boolean
    suspend fun disconnect()
    fun sendPointerEvent(flags: Int, x: Int, y: Int)
    fun sendKeyEvent(keyCode: Int, down: Boolean)
    fun sendUnicodeKeyEvent(unicodeChar: Char, down: Boolean)
    fun updateResolution(width: Int, height: Int, physicalWidthMm: Int, physicalHeightMm: Int, orientation: Int)
    fun sendClipboardText(text: String)
    fun setEventListener(listener: RdpEventListener?)
}
