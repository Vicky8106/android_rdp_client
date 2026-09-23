package com.freerdp.client.unit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Remote-frame pipeline: RdpEventListener.onGraphicsUpdate -> composited backing
 * bitmap -> single-slot FramePacer (blit-latest, drops stale frames) -> telemetry FPS.
 *
 * Native graphics mode is REQUIRED here: the default (legacy) shadows no-op
 * Canvas.drawBitmap, so the dirty-rect pixel assertions below would read the untouched
 * backing colour. NATIVE makes Bitmap/Canvas real Skia — the assertions verify actual
 * rasterization exactly as on-device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionGraphicsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    private fun tile(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    @Test
    fun graphicsUpdateCompositesIntoBackingFrameAndEntersPacer() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()
        h.mock.clearRecordedEvents()

        h.mock.triggerGraphicsUpdate(tile(8, 8, Color.RED), 4, 6, 8, 8)

        val frame = h.vm.currentFrame()
        assertNotNull("backing frame allocated on first update", frame)
        assertEquals("one frame decoded into the single-slot pacer", 1L, h.pacer.totalDecoded)
        assertTrue(h.pacer.hasPendingFrame)

        // Pixel-level verification that the dirty rect landed at the right offset.
        assertEquals(Color.RED, frame!!.getPixel(4, 6))
        assertEquals(Color.RED, frame.getPixel(11, 13))
        assertEquals(Color.BLACK, frame.getPixel(0, 0))
    }

    @Test
    fun rapidUpdatesDropStaleFramesAndAcquireDeliversTheLatest() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()

        // Two updates arrive before any VSYNC consumption.
        h.mock.triggerGraphicsUpdate(tile(4, 4, Color.GREEN), 0, 0, 4, 4)
        h.mock.triggerGraphicsUpdate(tile(4, 4, Color.BLUE), 0, 0, 4, 4)

        assertTrue("stale frame dropped by the single-slot pacer", h.pacer.totalDropped >= 1L)

        val blitted = h.pacer.acquireFrameForRendering()
        assertNotNull(blitted)
        // Same backing bitmap (composited in place) — the freshest content wins.
        assertSame(h.vm.currentFrame(), blitted)
        assertEquals(Color.BLUE, blitted!!.getPixel(0, 0))

        // Nothing pending after consumption: no spurious re-renders.
        assertNull(h.pacer.acquireFrameForRendering())
    }

    @Test
    fun resolutionChangeResizesBackingAndTracksRemoteSize() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()

        h.mock.updateResolution(320, 200, 80, 50, orientation = 1)
        pumpAll()

        assertEquals(Pair(320, 200), h.vm.remoteResolution.value)
        val frame = h.vm.currentFrame()
        assertNotNull(frame)
        assertEquals(320, frame!!.width)
        assertEquals(200, frame.height)
        // The real engine recorded the MS-RDPEDISP update too.
        assertEquals(1, h.mock.recordedResolutions.size)
    }

    @Test
    fun frameBlitsFeedTelemetryFrameCounters() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()

        h.mock.triggerGraphicsUpdate(tile(4, 4, Color.WHITE), 0, 0, 4, 4)
        val consumed = h.pacer.acquireFrameForRendering()
        assertNotNull(consumed)
        h.vm.onFrameBlitted()
        h.vm.onFrameBlitted()

        assertTrue(h.telemetry.getSnapshot().totalFramesRendered >= 2L)
    }

    @Test
    fun rttMetricsFromEngineFlowIntoTelemetry() = runTest {
        val h = sessionHarness(context, testProfile())
        h.vm.ensureStarted("profile-1")
        pumpAll()

        // MockRdpEngine starts with rttMs = 15; the VM seeds it on session start.
        assertEquals(15L, h.telemetry.getSnapshot().rttMs)

        h.mock.setMetrics(com.freerdp.core.engine.RdpSessionMetrics(rttMs = 42, fps = 60f))
        pumpAll()
        assertEquals(42L, h.telemetry.getSnapshot().rttMs)
    }
}
