package com.example.jizhang.automation

import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.graphics.PathIterator as ComposePathIterator
import androidx.compose.ui.graphics.PathSegment as ComposePathSegment
import androidx.compose.ui.graphics.asComposePath
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the shipped graphics JNI; passing on one ABI is not proof of all-device compatibility. */
@RunWith(AndroidJUnit4::class)
class NativeGraphicsInstrumentedTest {
    @Test
    fun composeIteratorConvertsActualOvalConicsToFiniteQuadratics() {
        // addOval is available before API 24 and produces Skia conics. Avoid API 34-only conicTo.
        val platformPath = Path().apply {
            addOval(RectF(10f, 20f, 110f, 120f), Path.Direction.CW)
        }
        val composePath = platformPath.asComposePath()
        val preserved = ComposePathIterator(
            composePath, ComposePathIterator.ConicEvaluation.AsConic,
            tolerance = 0.1f,
        )
        var preservedSegments = 0
        var hasActualConic = false
        while (preserved.hasNext()) {
            assertTrue("Synthetic oval traversal must terminate", ++preservedSegments < 256)
            val segment = preserved.next()
            if (segment.type == ComposePathSegment.Type.Conic) {
                hasActualConic = true
                assertTrue("Oval conic weight must be positive and finite",
                    segment.weight.isFinite() && segment.weight > 0f)
            }
        }
        assertTrue("Fixture must contain conics before conversion", hasActualConic)

        // Compose delegates to the same AndroidX iterator. On API 34+ its conic converter
        // still invokes libandroidx.graphics.path; earlier versions use the native iterator.
        val converted = ComposePathIterator(
            composePath,
            ComposePathIterator.ConicEvaluation.AsQuadratics,
            tolerance = 0.1f,
        )
        var convertedSegments = 0
        var quadratics = 0
        var coordinates = 0
        while (converted.hasNext()) {
            assertTrue("Converted traversal must terminate", ++convertedSegments < 256)
            val segment = converted.next()
            assertFalse("AsQuadratics must consume the original conics",
                segment.type == ComposePathSegment.Type.Conic)
            if (segment.type == ComposePathSegment.Type.Quadratic) quadratics++
            segment.points.forEach { coordinate ->
                assertTrue("JNI conversion must return finite coordinates", coordinate.isFinite())
                coordinates++
            }
        }
        assertTrue("Native conversion must produce quadratic curves", quadratics > 0)
        assertTrue("Converted path must contain coordinates", coordinates > 0)
    }
}
