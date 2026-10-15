package com.example.jizhang.automation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.BusinessType
import com.example.jizhang.domain.ScreenPaymentParser
import com.example.jizhang.recognition.CandidateFactory
import com.example.jizhang.recognition.DecisionEngine
import com.example.jizhang.recognition.EvidenceType
import com.example.jizhang.recognition.RecognitionDecision
import com.example.jizhang.recognition.ocr.OcrReceiptLayoutParser
import com.example.jizhang.ui.loadPaymentScreenshot
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalOcrInstrumentedTest {
    @Test
    fun actualSyntheticCanvasPixelsUseServiceScalingAndProduceATransferReviewCandidate() = runBlocking {
        // This test APK asset is a dedicated-emulator fixture, never a user's payment image.
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val decoded = testContext.assets.open("synthetic-canvas-transfer.png").use { BitmapFactory.decodeStream(it) }
        assertNotNull("Synthetic Canvas asset must decode", decoded)
        val original = decoded!!
        var scaled: Bitmap? = null
        try {
            val dimensions = ScreenImageLimits.outputSize(original.width, original.height)
            assertNotNull("Synthetic Canvas asset must satisfy the same service pixel limits", dimensions)
            val size = dimensions!!
            scaled = if (size.first == original.width && size.second == original.height) original else
                Bitmap.createScaledBitmap(original, size.first, size.second, true)
            val image = scaled!!
            assertEquals("Fixture must exercise the service's 2048-long-edge scaling", 2048, maxOf(image.width, image.height))
            val page = LocalTextRecognizer.recognizePage(image)
            val interpreted = OcrReceiptLayoutParser.interpret("com.tencent.mm", page)
            // Detailed text is permitted only in failed assertions for this fixed synthetic asset.
            val diagnostic = buildString {
                append("Synthetic Canvas asset ${page.width}x${page.height}; reasons=${interpreted.reasons}; amounts=${interpreted.amountOptions}\n")
                page.lines.forEachIndexed { index, line ->
                    append("line[$index] '${line.text}' bounds=${line.bounds}\n")
                    line.tokens.forEachIndexed { tokenIndex, token ->
                        append("  token[$tokenIndex] '${token.text}' bounds=${token.bounds}\n")
                    }
                }
            }
            assertNotNull(diagnostic, interpreted.payment)
            assertTrue(diagnostic, interpreted.amountOptions.distinct().size <= 1)
            val payment = interpreted.payment!!
            assertEquals(diagnostic, 2856L, payment.payment.amountCents)
            assertEquals(diagnostic, BusinessType.TRANSFER, payment.businessType)
            val candidate = CandidateFactory.fromScreen(payment, System.currentTimeMillis(), EvidenceType.OCR)
            assertEquals(diagnostic, RecognitionDecision.REVIEW, DecisionEngine.evaluate(candidate).decision)
        } finally {
            if (scaled !== original) scaled?.recycle()
            original.recycle()
        }
    }

    @Test
    fun bundledChineseModelReadsAnOfflineTransferReceiptForReview() = runBlocking {
        // Frozen pixels from the dedicated API24 synthetic fixture make this independent
        // of platform fallback fonts. Test APK only; contains no real user's payment image.
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val decoded = testContext.assets.open("synthetic-transfer-8866.png").use { BitmapFactory.decodeStream(it) }
        assertNotNull("Frozen synthetic transfer asset must decode", decoded)
        val bitmap = decoded!!
        try {
            val text = LocalTextRecognizer.recognize(bitmap)
            val payment = ScreenPaymentParser.parse("com.tencent.mm", text, fromOcr = true)
            assertNotNull("Synthetic receipt OCR: ${text.replace('\n', '|')}", payment)
            assertEquals(8866L, payment!!.payment.amountCents)
            assertEquals(EntryType.EXPENSE, payment.payment.type)
            assertTrue(payment.payment.needsReview)
        } finally { bitmap.recycle() }
    }

    @Test
    fun selectedScreenshotDecodesUsingBoundsProbeAndBoundedMemory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("screen-decode-test-", ".png", context.cacheDir)
        val original = Bitmap.createBitmap(1080, 2400, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { original.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val decoded = loadPaymentScreenshot(context, Uri.fromFile(file))
            try { assertEquals(1080, decoded.width); assertEquals(2400, decoded.height) }
            finally { decoded.recycle() }
        } finally { original.recycle(); file.delete() }
    }
}
