package com.rdp.client.ui.session.viewport

import android.content.res.Configuration
import android.util.Log
import com.rdp.client.freerdp.RdpSession
import com.rdp.client.model.ResolutionMode
import com.rdp.client.model.ServerProfile

/**
 * Controller managing dynamic display resizing (MS-RDPEDISP protocol channel)
 * and per-orientation zoom persistence (zoom1/zoom2).
 */
class DisplayResizeManager(
    private val frameView: FrameView,
    private val profile: ServerProfile
) {
    private val TAG = "DisplayResizeManager"
    private var lastOrientation: Int = Configuration.ORIENTATION_UNDEFINED

    /**
     * Handles device orientation changes triggered by onConfigurationChanged().
     */
    fun onConfigurationChanged(newConfig: Configuration, session: RdpSession?) {
        if (newConfig.orientation == lastOrientation) return
        lastOrientation = newConfig.orientation

        Log.i(TAG, "Orientation shifted to: ${if (lastOrientation == Configuration.ORIENTATION_LANDSCAPE) "LANDSCAPE" else "PORTRAIT"}")

        // 1. Switch active per-orientation zoom factor
        val targetZoom = if (lastOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            profile.zoom2 // Landscape zoom preference
        } else {
            profile.zoom1 // Portrait zoom preference
        }
        frameView.setZoomScale(targetZoom)

        // 2. Dynamic display resizing (MS-RDPEDISP)
        if (profile.resolutionMode == ResolutionMode.DYNAMIC && session != null) {
            requestDynamicResolutionUpdate(session)
        }
    }

    /**
     * Calculates optimal remote desktop resolution matching physical viewport aspect ratio.
     */
    private fun requestDynamicResolutionUpdate(session: RdpSession) {
        val vpWidth = frameView.width
        val vpHeight = frameView.height
        if (vpWidth <= 0 || vpHeight <= 0) return

        // Align dimensions to 4-pixel boundaries required by RDP codecs
        val alignedWidth = (vpWidth / 4) * 4
        val alignedHeight = (vpHeight / 4) * 4

        Log.i(TAG, "Requesting dynamic MS-RDPEDISP resize: ${alignedWidth}x${alignedHeight}")
        frameView.setRemoteResolution(alignedWidth, alignedHeight)
    }

    /**
     * Saves the current zoom factor back to the profile entity before exit.
     */
    fun saveOrientationZoomToProfile(): ServerProfile {
        val currentZoom = frameView.getZoomScale()
        return if (lastOrientation == Configuration.ORIENTATION_LANDSCAPE) {
            profile.copy(zoom2 = currentZoom)
        } else {
            profile.copy(zoom1 = currentZoom)
        }
    }
}
