package com.example.jizhang.recognition.ocr

/** Temporary input-image pixel coordinates, never a persisted screen dump. */
data class OcrRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isValid: Boolean get() = width > 0 && height > 0
    fun inside(width: Int, height: Int): Boolean = isValid && left >= 0 && top >= 0 && right <= width && bottom <= height
    fun overlaps(other: OcrRect): Boolean = isValid && other.isValid &&
        left < other.right && right > other.left && top < other.bottom && bottom > other.top
}

data class OcrToken(val text: String, val bounds: OcrRect?)

data class OcrLine(
    val text: String,
    val bounds: OcrRect?,
    val blockIndex: Int = 0,
    val lineIndex: Int = 0,
    val tokens: List<OcrToken> = emptyList(),
)

data class OcrDocument(
    val width: Int,
    val height: Int,
    val lines: List<OcrLine>,
    val elapsedMs: Long = 0,
    val queueWaitMs: Long = 0,
    val truncated: Boolean = false,
) {
    /** Compatibility only. Spatial receipt parsing consumes lines/tokens directly. */
    fun plainText(): String = lines.joinToString("\n") { it.text }
}
