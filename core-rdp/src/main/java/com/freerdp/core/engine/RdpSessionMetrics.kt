package com.freerdp.core.engine

data class RdpSessionMetrics(
    val rttMs: Long = 0L,
    val fps: Float = 0f,
    val bandwidthKbps: Long = 0L,
    val frameCount: Long = 0L,
    val droppedFrames: Long = 0L,
    val jitterMs: Long = 0L
)
