package com.sudoku.solver.processing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.opencv.android.OpenCVLoader
import kotlin.math.roundToInt

class ScreenDigitMatcherDeviceTest {
    @Test fun recognizesHeldOutBoardAndAbstainsOnBlankCells() {
        assertTrue(OpenCVLoader.initLocal())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val matcher = ScreenDigitMatcher(instrumentation.targetContext)
        val screenshot = instrumentation.context.assets.open("fire_hd10_9x9_2026_10_14.png").use {
            BitmapFactory.decodeStream(it)
        }
        val expected = listOf("000009000", "093700165", "870010902", "361000584", "740601293",
            "950803671", "607428059", "034970016", "089036007")
        try {
            val board = requireNotNull(BoardDetector().detectBoard(screenshot))
            try {
                val edges = IntArray(10) { (6 + it * (1404 - 12) / 9.0).roundToInt() }
                expected.forEachIndexed { row, digits ->
                    digits.forEachIndexed { col, digit ->
                        val cell = Bitmap.createBitmap(board.warpedBitmap, edges[col], edges[row],
                            edges[col + 1] - edges[col], edges[row + 1] - edges[row])
                        try {
                            assertEquals("r${row + 1}c${col + 1}", digit.digitToInt(), matcher.match(cell)?.digit ?: 0)
                        } finally { cell.recycle() }
                    }
                }
            } finally { board.recycle() }
        } finally { screenshot.recycle() }
    }

    @Test fun rejectsHighlightsGridFragmentsPencilMarksAndUnknownShapes() {
        assertTrue(OpenCVLoader.initLocal())
        val matcher = ScreenDigitMatcher(InstrumentationRegistry.getInstrumentation().targetContext)
        val bitmap = Bitmap.createBitmap(156, 156, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 3f }
        try {
            for (background in listOf(Color.WHITE, Color.rgb(137, 171, 255), Color.BLACK)) {
                bitmap.eraseColor(background)
                assertNull("uniform background $background", matcher.match(bitmap))
            }
            bitmap.eraseColor(Color.WHITE)
            canvas.drawLine(25f, 70f, 130f, 70f, paint)
            assertNull("horizontal grid fragment", matcher.match(bitmap))
            bitmap.eraseColor(Color.WHITE)
            paint.textSize = 18f
            for (index in 0..8) canvas.drawText("${index + 1}", 28f + index % 3 * 34,
                45f + index / 3 * 34, paint)
            assertNull("pencil marks", matcher.match(bitmap))
            bitmap.eraseColor(Color.WHITE)
            paint.strokeWidth = 8f
            canvas.drawLine(48f, 43f, 107f, 113f, paint)
            canvas.drawLine(107f, 43f, 48f, 113f, paint)
            assertNull("unknown X glyph", matcher.match(bitmap))
        } finally { bitmap.recycle() }
    }
}
