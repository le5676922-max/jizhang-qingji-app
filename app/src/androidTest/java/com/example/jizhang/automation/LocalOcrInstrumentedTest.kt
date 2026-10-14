package com.example.jizhang.automation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.jizhang.domain.EntryType
import com.example.jizhang.domain.ScreenPaymentParser
import com.example.jizhang.ui.loadPaymentScreenshot
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalOcrInstrumentedTest {
    @Test
    fun bundledChineseModelReadsAnOfflineTransferReceiptForReview() = runBlocking {
        val bitmap = Bitmap.createBitmap(1000, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 48f; typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        listOf("转账详情", "转账成功", "转账金额：88.66元", "收款人：测试用户", "交易单号：OCR20261013TEST").forEachIndexed { index, line ->
            canvas.drawText(line, 60f, 100f + index * 125f, paint)
        }
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
