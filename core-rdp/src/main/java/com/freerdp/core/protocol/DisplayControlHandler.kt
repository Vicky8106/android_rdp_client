package com.freerdp.core.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DisplayControlHandler(
    private val debounceDelayMs: Long = 250L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val onLayoutUpdate: (layout: MonitorLayout) -> Unit
) {
    data class MonitorLayout(
        val flags: Int = MONITOR_PRIMARY,
        val left: Int = 0,
        val top: Int = 0,
        val width: Int,
        val height: Int,
        val physicalWidthMm: Int,
        val physicalHeightMm: Int,
        val orientation: Int,
        val desktopScaleFactor: Int = 100,
        val deviceScaleFactor: Int = 100
    ) {
        companion object {
            const val MONITOR_PRIMARY = 0x00000001
            const val ORIENTATION_LANDSCAPE = 0
            const val ORIENTATION_PORTRAIT = 1
            const val MIN_WIDTH = 640
            const val MIN_HEIGHT = 480
        }
    }

    private var debounceJob: Job? = null
    private var lastDispatchedLayout: MonitorLayout? = null

    companion object {
        fun alignDimension(dim: Int, min: Int = 640): Int {
            val aligned = dim and 3.inv()
            return if (aligned < min) min else aligned
        }

        fun calculatePhysicalDimensionMm(pixels: Int, dpi: Float): Int {
            if (dpi <= 0f) return 0
            return ((pixels / dpi) * 25.4f).toInt()
        }
    }

    fun requestLayoutUpdate(
        width: Int,
        height: Int,
        dpi: Float = 160f,
        orientation: Int = if (width >= height) MonitorLayout.ORIENTATION_LANDSCAPE else MonitorLayout.ORIENTATION_PORTRAIT,
        immediate: Boolean = false,
        physicalWidthMm: Int = 0,
        physicalHeightMm: Int = 0
    ) {
        val alignedWidth = alignDimension(width, MonitorLayout.MIN_WIDTH)
        val alignedHeight = alignDimension(height, MonitorLayout.MIN_HEIGHT)
        // Real device dimensions win over dpi-derived estimates: the
        // DISPLAY_CONTROL_MONITOR_LAYOUT PDU (MS-RDPEDISP §2.2.2.2) carries physical mm.
        val physWidth =
            if (physicalWidthMm > 0) physicalWidthMm else calculatePhysicalDimensionMm(alignedWidth, dpi)
        val physHeight =
            if (physicalHeightMm > 0) physicalHeightMm else calculatePhysicalDimensionMm(alignedHeight, dpi)

        val layout = MonitorLayout(
            width = alignedWidth,
            height = alignedHeight,
            physicalWidthMm = physWidth,
            physicalHeightMm = physHeight,
            orientation = orientation
        )

        debounceJob?.cancel()
        if (immediate || debounceDelayMs <= 0) {
            dispatchLayout(layout)
        } else {
            debounceJob = scope.launch {
                delay(debounceDelayMs)
                dispatchLayout(layout)
            }
        }
    }

    private fun dispatchLayout(layout: MonitorLayout) {
        lastDispatchedLayout = layout
        onLayoutUpdate(layout)
    }

    fun getLastDispatchedLayout(): MonitorLayout? = lastDispatchedLayout

    fun cancelPending() {
        debounceJob?.cancel()
    }
}
