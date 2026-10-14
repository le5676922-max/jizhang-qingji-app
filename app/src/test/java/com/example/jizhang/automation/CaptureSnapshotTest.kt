package com.example.jizhang.automation

import org.junit.Assert.*
import org.junit.Test

class CaptureSnapshotTest {
    private val epoch = ForegroundEpoch("com.tencent.mm", "TransferResultActivity", 4, 3, 500, 400, 2, 8)
    private val bounds = ScreenNodeBounds(0, 0, 1080, 2400)
    private val capture = CaptureSnapshot(epoch, bounds)
    private fun valid(current: ForegroundEpoch = epoch, generation: Long = 8, currentBounds: ScreenNodeBounds = bounds,
                      connected: Boolean = true, screen: Boolean = true, source: Boolean = true,
                      ocr: Boolean = true, requiresOcr: Boolean = false) =
        capture.valid(current, generation, currentBounds, connected, screen, source, ocr, requiresOcr)

    @Test fun stableCaptureMayCommit() = assertTrue(valid())
    @Test fun anotherWindowOrSessionInvalidatesOldReceipt() {
        assertFalse(valid(epoch.copy(windowId = 5)))
        assertFalse(valid(epoch.copy(session = 4)))
        assertFalse(valid(epoch.copy(packageName = "com.eg.android.AlipayGphone")))
    }
    @Test fun sameWindowContentRevisionAndChangedTimeBothInvalidate() {
        assertFalse(valid(epoch.copy(revision = 3)))
        assertFalse(valid(epoch.copy(changedAt = 501)))
    }
    @Test fun disablingAndReenablingUsesConfigurationGeneration() {
        assertFalse(valid(generation = 9))
        assertFalse(valid(epoch.copy(configurationGeneration = 10), generation = 10))
    }
    @Test fun connectionAndPerSourceRevocationInvalidate() {
        assertFalse(valid(connected = false))
        assertFalse(valid(screen = false))
        assertFalse(valid(source = false))
        assertFalse(valid(ocr = false, requiresOcr = true))
        assertTrue(valid(ocr = false, requiresOcr = false))
    }
    @Test fun rotationWithSameActivityAndWindowStillInvalidatesGeometry() {
        assertFalse(valid(currentBounds = ScreenNodeBounds(0, 0, 2400, 1080)))
    }
    @Test fun apiCapabilitiesAreGuardedAt24Through34() {
        listOf(24, 29).forEach { assertEquals(ScreenCaptureMode.NODES_ONLY, ScreenCapabilities.forApi(it).mode) }
        listOf(30, 33).forEach { assertEquals(ScreenCaptureMode.DISPLAY, ScreenCapabilities.forApi(it).mode) }
        assertEquals(ScreenCaptureMode.WINDOW, ScreenCapabilities.forApi(34).mode)
        assertFalse(ScreenCapabilities.forApi(33).sensitiveNodeMetadata)
        assertTrue(ScreenCapabilities.forApi(34).sensitiveNodeMetadata)
        assertEquals(ScreenCaptureMode.NODES_ONLY, ScreenCapabilities.forApi(34, false).mode)
    }
    @Test fun oversizedOrInvalidInputIsRejectedAndOutputIsBounded() {
        assertNull(ScreenImageLimits.outputSize(0, 200))
        assertNull(ScreenImageLimits.outputSize(100_000, 100_000))
        val (width, height) = requireNotNull(ScreenImageLimits.outputSize(1440, 3088))
        assertTrue(maxOf(width, height) <= ScreenImageLimits.MAX_OUTPUT_EDGE)
        assertTrue(width.toLong() * height <= ScreenImageLimits.MAX_OUTPUT_PIXELS)
        assertEquals(600 to 800, ScreenImageLimits.outputSize(600, 800))
    }
}
