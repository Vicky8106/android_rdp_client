package com.freerdp.feature.telemetry.display

import com.freerdp.core.engine.IRdpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Dynamic orientation, multi-window, and foldable layout listener with adaptive render resizing.
 *
 * Listens for viewport dimension changes when the device rotates, unfolds (foldables),
 * or enters split-screen multi-window mode. Resizes the remote desktop session via
 * MS-RDPEDISP ([IRdpEngine.updateResolution]) with debouncing to prevent flooding
 * the native session during continuous animations or fold gestures.
 */
class DynamicLayoutListener(
    private val engine: IRdpEngine,
    private val coroutineScope: CoroutineScope,
    val debounceDelayMs: Long = DEFAULT_DEBOUNCE_DELAY_MS,
    var onResolutionUpdatedListener: ((width: Int, height: Int, orientation: Int) -> Unit)? = null
) {

    companion object {
        /**
         * Default debounce window: 250 ms (TEST_INFRA Tier-2 "250ms debounce coalescing").
         * Long enough to coalesce fold/unfold & rotation animations, short enough that a
         * deliberate resize feels instant.
         */
        const val DEFAULT_DEBOUNCE_DELAY_MS: Long = 250L
    }

    private var resizeJob: Job? = null

    // Last applied layout — guarded by stateLock so the duplicate check and the apply
    // step are consistent across the caller thread and the debounce coroutine.
    private val stateLock = Any()
    private var lastAppliedWidth = 0
    private var lastAppliedHeight = 0
    private var lastAppliedOrientation = -1

    private val _resizeEventsCount = AtomicInteger(0)
    val resizeEventsCount: Int get() = _resizeEventsCount.get()

    /**
     * Handles raw pixel dimensions and physical metrics change.
     *
     * Zero/negative dimensions are rejected (boundary guard: never send a degenerate
     * MS-RDPEDISP layout PDU to the native session).
     */
    fun onLayoutChanged(
        newWidth: Int,
        newHeight: Int,
        physicalWidthMm: Int = 0,
        physicalHeightMm: Int = 0,
        orientation: Int = 0,
        immediate: Boolean = false
    ) {
        if (newWidth <= 0 || newHeight <= 0) return

        // Skip redundant updates if dimensions and orientation match
        synchronized(stateLock) {
            if (newWidth == lastAppliedWidth && newHeight == lastAppliedHeight && orientation == lastAppliedOrientation) {
                return
            }
        }

        resizeJob?.cancel()

        if (immediate || debounceDelayMs <= 0L) {
            applyResolution(newWidth, newHeight, physicalWidthMm, physicalHeightMm, orientation)
        } else {
            resizeJob = coroutineScope.launch {
                delay(debounceDelayMs)
                applyResolution(newWidth, newHeight, physicalWidthMm, physicalHeightMm, orientation)
            }
        }
    }

    /**
     * Handles window size in pixels and density DPI, automatically calculating physical millimeters.
     */
    fun onWindowSizeChanged(
        widthPx: Int,
        heightPx: Int,
        densityDpi: Int,
        orientation: Int = 0,
        immediate: Boolean = false
    ) {
        val safeDpi = densityDpi.coerceAtLeast(1)
        val physicalWidthMm = ((widthPx * 25.4f) / safeDpi).toInt()
        val physicalHeightMm = ((heightPx * 25.4f) / safeDpi).toInt()

        onLayoutChanged(
            newWidth = widthPx,
            newHeight = heightPx,
            physicalWidthMm = physicalWidthMm,
            physicalHeightMm = physicalHeightMm,
            orientation = orientation,
            immediate = immediate
        )
    }

    private fun applyResolution(
        width: Int,
        height: Int,
        physicalWidthMm: Int,
        physicalHeightMm: Int,
        orientation: Int
    ) {
        synchronized(stateLock) {
            lastAppliedWidth = width
            lastAppliedHeight = height
            lastAppliedOrientation = orientation
        }
        _resizeEventsCount.incrementAndGet()

        engine.updateResolution(
            width = width,
            height = height,
            physicalWidthMm = physicalWidthMm,
            physicalHeightMm = physicalHeightMm,
            orientation = orientation
        )
        onResolutionUpdatedListener?.invoke(width, height, orientation)
    }

    /**
     * Cancels any pending debounced resize operations (e.g. app backgrounding mid-fold).
     */
    fun cancelPending() {
        resizeJob?.cancel()
        resizeJob = null
    }
}
