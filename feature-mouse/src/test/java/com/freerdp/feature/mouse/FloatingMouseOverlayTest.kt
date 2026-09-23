package com.freerdp.feature.mouse

import android.content.Context
import android.content.SharedPreferences
import android.view.MotionEvent
import org.robolectric.RuntimeEnvironment
import com.freerdp.core.engine.MockRdpEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloatingMouseOverlayTest {

    private lateinit var context: Context
    private lateinit var overlayView: FloatingMouseOverlayView
    private lateinit var mockEngine: MockRdpEngine
    private lateinit var transformer: CoordinateTransformer
    private lateinit var mouseController: DefaultMouseController
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        overlayView = FloatingMouseOverlayView(context)

        mockEngine = MockRdpEngine()
        transformer = CoordinateTransformer(1920, 1080, 1080, 2400)
        mouseController = DefaultMouseController(mockEngine, transformer)
        overlayView.mouseController = mouseController

        prefs = context.getSharedPreferences("test_overlay_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    @Test
    fun testInitialStateIsCollapsed() {
        assertEquals(OverlayState.COLLAPSED, overlayView.state)
        assertEquals(android.view.View.VISIBLE, overlayView.bubbleView.visibility)
        assertEquals(android.view.View.GONE, overlayView.paletteView.visibility)
    }

    @Test
    fun testOverlayStateTransitions() {
        overlayView.expand()
        assertEquals(OverlayState.EXPANDED, overlayView.state)
        assertEquals(android.view.View.GONE, overlayView.bubbleView.visibility)
        assertEquals(android.view.View.VISIBLE, overlayView.paletteView.visibility)

        overlayView.collapse()
        assertEquals(OverlayState.COLLAPSED, overlayView.state)
        assertEquals(android.view.View.VISIBLE, overlayView.bubbleView.visibility)
        assertEquals(android.view.View.GONE, overlayView.paletteView.visibility)

        // Close button in palette collapses
        overlayView.expand()
        overlayView.btnCollapse.performClick()
        assertEquals(OverlayState.COLLAPSED, overlayView.state)
    }

    @Test
    fun testSafeInsetClampingOnDrag() {
        val insets = SafeInsets(left = 40, top = 80, right = 40, bottom = 120)
        overlayView.setSafeInsets(insets)

        // Drag to negative coordinates (-50, -50)
        overlayView.updatePosition(-50f, -50f)
        assertEquals(40f, overlayView.currentScreenX, 0.01f) // Clamped to insets.left
        assertEquals(80f, overlayView.currentScreenY, 0.01f) // Clamped to insets.top

        // Drag to extreme positive coordinates (10000, 10000)
        overlayView.updatePosition(10000f, 10000f)
        // With default screen 1080x2400, bubble size 48dp (density = 1 -> 48px)
        // maxX = 1080 - 48 - 40 = 992
        // maxY = 2400 - 48 - 120 = 2232
        val expectedMaxX = 1080f - overlayView.bubbleSizePx - 40f
        val expectedMaxY = 2400f - overlayView.bubbleSizePx - 120f
        assertEquals(expectedMaxX, overlayView.currentScreenX, 0.01f)
        assertEquals(expectedMaxY, overlayView.currentScreenY, 0.01f)
    }

    @Test
    fun testEdgeSnappingBehavior() {
        val screenW = 1080
        val screenH = 2400
        val insets = SafeInsets(left = 20, top = 50, right = 20, bottom = 50)
        val overlayW = 48
        val overlayH = 48

        // Release at x = 200 (left half of screenW = 1080)
        val snapLeft = OverlayCoordinates.snapToNearestEdge(
            currentX = 200f,
            currentY = 500f,
            screenWidth = screenW,
            screenHeight = screenH,
            overlayWidth = overlayW,
            overlayHeight = overlayH,
            insets = insets
        )
        assertEquals(20f, snapLeft.x, 0.01f) // Snapped to minX (insets.left)
        assertEquals(500f, snapLeft.y, 0.01f)

        // Release at x = 800 (right half of screenW = 1080)
        val snapRight = OverlayCoordinates.snapToNearestEdge(
            currentX = 800f,
            currentY = 500f,
            screenWidth = screenW,
            screenHeight = screenH,
            overlayWidth = overlayW,
            overlayHeight = overlayH,
            insets = insets
        )
        val expectedMaxX = (screenW - overlayW - insets.right).toFloat()
        assertEquals(expectedMaxX, snapRight.x, 0.01f) // Snapped to maxX
        assertEquals(500f, snapRight.y, 0.01f)
    }

    @Test
    fun testOrientationPersistenceAcrossScreenRotation() {
        // Set normalized coordinates in portrait
        val normCoords = OverlayCoordinates(normalizedX = 0.9f, normalizedY = 0.5f)
        overlayView.setOverlayCoordinates(normCoords)

        // In portrait (1080 x 2400):
        val portraitCoords = normCoords.toScreenCoordinates(
            screenWidth = 1080,
            screenHeight = 2400,
            overlayWidth = overlayView.bubbleSizePx,
            overlayHeight = overlayView.bubbleSizePx
        )
        assertTrue(portraitCoords.x in 0f..1080f)
        assertTrue(portraitCoords.y in 0f..2400f)

        // Simulate screen rotation to landscape (2400 x 1080)
        overlayView.updateDisplayBounds(2400, 1080)

        // Must recompute from normalized coordinates without resetting or clipping
        assertEquals(0.9f, overlayView.currentCoordinates.normalizedX, 0.01f)
        assertEquals(0.5f, overlayView.currentCoordinates.normalizedY, 0.01f)
        assertTrue(overlayView.currentScreenX in 0f..2400f)
        assertTrue(overlayView.currentScreenY in 0f..1080f)
    }

    @Test
    fun testSharedPreferencesSaveAndRestore() {
        val customCoords = OverlayCoordinates(normalizedX = 0.15f, normalizedY = 0.75f)
        overlayView.setOverlayCoordinates(customCoords)
        overlayView.savePosition(prefs)

        // Create new overlay and restore
        val newOverlay = FloatingMouseOverlayView(context)
        newOverlay.restorePosition(prefs)

        assertEquals(0.15f, newOverlay.currentCoordinates.normalizedX, 0.001f)
        assertEquals(0.75f, newOverlay.currentCoordinates.normalizedY, 0.001f)
    }

    @Test
    fun testControlButtonsDispatchToMouseController() {
        overlayView.expand()

        // Test Left Click Button
        mockEngine.clearRecordedEvents()
        overlayView.btnLeftClick.performClick()
        assertEquals(2, mockEngine.recordedPointerEvents.size)

        // Test Right Click Button
        mockEngine.clearRecordedEvents()
        overlayView.btnRightClick.performClick()
        assertEquals(2, mockEngine.recordedPointerEvents.size)

        // Test Drag Lock Button Toggle
        assertFalse(overlayView.isDragLocked)
        overlayView.btnDragToggle.performClick()
        assertTrue(overlayView.isDragLocked)
        assertTrue(mouseController.isDragging)

        overlayView.btnDragToggle.performClick()
        assertFalse(overlayView.isDragLocked)
        assertFalse(mouseController.isDragging)

        // Test Scroll Rocker
        mockEngine.clearRecordedEvents()
        overlayView.btnScrollUp.performClick()
        assertEquals(1, mockEngine.recordedPointerEvents.size)
        assertEquals(com.freerdp.core.protocol.RdpPointerFlags.SCROLL_UP, mockEngine.recordedPointerEvents[0].flags)

        mockEngine.clearRecordedEvents()
        overlayView.btnScrollDown.performClick()
        assertEquals(1, mockEngine.recordedPointerEvents.size)
        assertEquals(com.freerdp.core.protocol.RdpPointerFlags.SCROLL_DOWN, mockEngine.recordedPointerEvents[0].flags)

        // Test Touchpad Mode Toggle
        assertFalse(overlayView.isTouchpadActive)
        overlayView.btnTouchpadToggle.performClick()
        assertTrue(overlayView.isTouchpadActive)
        assertTrue(mouseController.isTouchpadMode)
        assertTrue(mouseController.isCursorVisible)

        // Test Cursor Visibility Toggle
        overlayView.btnCursorToggle.performClick()
        assertFalse(mouseController.isCursorVisible)
    }
}
