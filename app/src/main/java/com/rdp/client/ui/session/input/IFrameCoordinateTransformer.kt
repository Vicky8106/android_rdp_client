package com.rdp.client.ui.session.input

import android.graphics.PointF

/**
 * Coordinate Transformer Interface providing coordinate mapping and viewport manipulation
 * for touch dispatchers and input controllers.
 */
interface IFrameCoordinateTransformer {
    val fbWidth: Int
    val fbHeight: Int
    fun toFb(vpX: Float, vpY: Float): PointF?
    fun toVp(fbX: Float, fbY: Float): PointF
    fun panFrame(dx: Float, dy: Float)
    fun zoomFrame(scaleFactor: Float, focusX: Float, focusY: Float)
    val safeAreaCenterX: Float
    val safeAreaCenterY: Float
}
