package com.example.jizhang.automation

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import com.example.jizhang.recognition.ocr.OcrDocument
import com.example.jizhang.recognition.ocr.OcrLine
import com.example.jizhang.recognition.ocr.OcrRect
import com.example.jizhang.recognition.ocr.OcrToken
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** Bundled Chinese model. The caller owns the bitmap and must recycle it after this method returns. */
object LocalTextRecognizer {
    private val nativeTask = Mutex()

    suspend fun recognize(bitmap: Bitmap): String = recognizePage(bitmap).plainText()

    /** The process-wide gate remains held until native completion, including caller cancellation. */
    suspend fun recognizePage(bitmap: Bitmap): OcrDocument {
        val queuedAt = SystemClock.elapsedRealtime()
        return nativeTask.withLock {
            withContext(Dispatchers.IO) {
                coroutineContext.ensureActive()
                val startedAt = SystemClock.elapsedRealtime()
                withContext(NonCancellable) {
                    process(bitmap, startedAt, startedAt - queuedAt)
                }
            }
        }
    }

    private suspend fun process(bitmap: Bitmap, startedAt: Long, queueWaitMs: Long): OcrDocument = suspendCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnCompleteListener(DIRECT_EXECUTOR) { task ->
                    // Await the native task even if the caller's Job is cancelled: this prevents
                    // a caller's finally block from recycling an image ML Kit is still reading.
                    val outcome = runCatching {
                        if (task.isSuccessful) {
                            document(task.result, bitmap.width, bitmap.height,
                                SystemClock.elapsedRealtime() - startedAt, queueWaitMs)
                        } else {
                            throw task.exception ?: IllegalStateException("Local recognition failed")
                        }
                    }
                    // Close before resuming/unlocking, so the next caller cannot overlap resources.
                    runCatching { recognizer.close() }
                    outcome.fold(continuation::resume, continuation::resumeWithException)
                }
        } catch (failure: Exception) {
            recognizer.close()
            continuation.resumeWithException(failure)
        }
    }

    private fun document(text: Text, width: Int, height: Int, elapsedMs: Long, queueWaitMs: Long): OcrDocument {
        val lines = mutableListOf<OcrLine>()
        var characters = 0
        var tokenCount = 0
        var truncated = false
        outer@ for ((blockIndex, block) in text.textBlocks.withIndex()) {
            for ((lineIndex, line) in block.lines.withIndex()) {
                val lineText = line.text
                val elements = line.elements
                val addition = lineText.length + elements.sumOf { it.text.length }
                if (lines.size >= 128 || elements.size > 32 || tokenCount + elements.size > 512 ||
                    lineText.length > 256 || characters + addition > ScreenCapturePolicy.MAX_TEXT_LENGTH) {
                    truncated = true
                    break@outer
                }
                lines.add(OcrLine(lineText, line.boundingBox?.asOcrRect(), blockIndex, lineIndex,
                    elements.map { OcrToken(it.text, it.boundingBox?.asOcrRect()) }))
                characters += addition
                tokenCount += elements.size
            }
        }
        return OcrDocument(width, height, lines, elapsedMs, queueWaitMs, truncated)
    }

    private fun Rect.asOcrRect() = OcrRect(left, top, right, bottom)

    private val DIRECT_EXECUTOR = Executor { it.run() }
}
