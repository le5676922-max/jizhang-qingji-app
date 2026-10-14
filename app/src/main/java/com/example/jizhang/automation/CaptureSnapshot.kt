package com.example.jizhang.automation

/** Monotonic content/configuration identity, independent of Android lifecycle objects. */
internal data class ForegroundEpoch(
    val packageName: String = "",
    val activityClass: String = "",
    val windowId: Int = -1,
    val session: Long = 0,
    val changedAt: Long = 0,
    val lastStateEventTime: Long = 0,
    val revision: Long = 0,
    val configurationGeneration: Long = 0,
    val lastContentEventTime: Long = 0,
) {
    fun sameObservation(other: ForegroundEpoch): Boolean = windowId >= 0 &&
        packageName == other.packageName && activityClass == other.activityClass && windowId == other.windowId &&
        session == other.session && revision == other.revision && changedAt == other.changedAt &&
        configurationGeneration == other.configurationGeneration
}

internal data class CaptureSnapshot(
    val epoch: ForegroundEpoch,
    val bounds: ScreenNodeBounds,
) {
    fun valid(
        current: ForegroundEpoch,
        configurationGeneration: Long,
        currentBounds: ScreenNodeBounds,
        connected: Boolean,
        screenEnabled: Boolean,
        sourceEnabled: Boolean,
        ocrEnabled: Boolean,
        requiresOcr: Boolean,
    ): Boolean = connected && screenEnabled && sourceEnabled && (!requiresOcr || ocrEnabled) &&
        epoch.configurationGeneration == configurationGeneration && epoch.sameObservation(current) &&
        !bounds.isEmpty && bounds == currentBounds
}

internal enum class ScreenCaptureMode { NODES_ONLY, DISPLAY, WINDOW }

internal data class ScreenCapabilities(val mode: ScreenCaptureMode, val sensitiveNodeMetadata: Boolean) {
    companion object {
        fun forApi(api: Int, screenshotCapability: Boolean = true): ScreenCapabilities = ScreenCapabilities(
            when {
                api < 30 || !screenshotCapability -> ScreenCaptureMode.NODES_ONLY
                api >= 34 -> ScreenCaptureMode.WINDOW
                else -> ScreenCaptureMode.DISPLAY
            }, api >= 34,
        )
    }
}

internal object ScreenImageLimits {
    const val MAX_INPUT_PIXELS = 16_000_000L
    const val MAX_OUTPUT_PIXELS = 3_000_000L
    const val MAX_OUTPUT_EDGE = 2_048

    fun outputSize(width: Int, height: Int): Pair<Int, Int>? {
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_INPUT_PIXELS) return null
        val scale = minOf(1.0, MAX_OUTPUT_EDGE.toDouble() / maxOf(width, height),
            kotlin.math.sqrt(MAX_OUTPUT_PIXELS.toDouble() / (width.toLong() * height)))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
}
