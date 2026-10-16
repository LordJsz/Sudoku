package com.sudoku.solver.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Recognizes the game's printed font from verified glyphs, independently of Sudoku
 * solutions and OCR guesses. Tight distance AND runner-up margin checks make this
 * an optional fast path; unfamiliar fonts and damaged crops go to normal OCR.
 *
 * Reference provenance lives beside the atlas in assets/ocr/screen_digits.json.
 */
internal class ScreenDigitMatcher(context: Context) {
    private val references: List<Pair<Int, FloatArray>> = loadReferences(context)

    data class Match(val digit: Int, val distance: Float, val margin: Float)

    fun match(cell: Bitmap): Match? {
        if (references.isEmpty()) return null
        val shape = try { describe(cell) } catch (error: RuntimeException) {
            Log.w(TAG, "Printed-font matching failed; using OCR", error)
            null
        } ?: return null
        val ranked = (1..9).map { digit ->
            digit to references.asSequence().filter { it.first == digit }
                .minOf { distance(shape, it.second) }
        }.sortedBy { it.second }
        val best = ranked[0]
        val margin = ranked[1].second - best.second
        return if (best.second <= MAX_DISTANCE && margin >= MIN_MARGIN) {
            Match(best.first, best.second, margin)
        } else null
    }

    private fun loadReferences(context: Context): List<Pair<Int, FloatArray>> = try {
        val atlas = context.assets.open("ocr/screen_digits.png").use { BitmapFactory.decodeStream(it) }
        try {
            require(atlas.width == WIDTH * 9 && atlas.height == HEIGHT * 3)
            buildList {
                for (sample in 0..2) for (digit in 1..9) {
                    val pixels = IntArray(WIDTH * HEIGHT)
                    atlas.getPixels(pixels, 0, WIDTH, (digit - 1) * WIDTH, sample * HEIGHT, WIDTH, HEIGHT)
                    add(digit to FloatArray(pixels.size) { (pixels[it] and 0xff) / 255f })
                }
            }
        } finally { atlas.recycle() }
    } catch (error: Exception) {
        Log.w(TAG, "Printed-font references unavailable; using OCR", error)
        emptyList()
    }

    private fun describe(cell: Bitmap): FloatArray? {
        val margin = (minOf(cell.width, cell.height) * 0.14f).toInt()
        val width = cell.width - margin * 2
        val height = cell.height - margin * 2
        if (width < 8 || height < 8) return null
        val rgba = Mat()
        val gray = Mat()
        val ink = Mat()
        val labels = Mat()
        val stats = Mat()
        val centers = Mat()
        val isolated = Mat()
        val normalized = Mat()
        var interior: Mat? = null
        var component: Mat? = null
        try {
            Utils.bitmapToMat(cell, rgba)
            interior = rgba.submat(Rect(margin, margin, width, height))
            Imgproc.cvtColor(interior, gray, Imgproc.COLOR_RGBA2GRAY)
            val range = Core.minMaxLoc(gray)
            // Uniform selected cells must never turn into digit templates.
            if (range.maxVal - range.minVal < 40) return null
            Imgproc.threshold(gray, ink, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
            if (Core.countNonZero(ink) > width * height / 2) Core.bitwise_not(ink, ink)
            val count = Imgproc.connectedComponentsWithStats(ink, labels, stats, centers)
            if (count <= 1) return null
            val largest = (1 until count).maxBy { stats.get(it, Imgproc.CC_STAT_AREA)[0] }
            val left = stats.get(largest, Imgproc.CC_STAT_LEFT)[0].toInt()
            val top = stats.get(largest, Imgproc.CC_STAT_TOP)[0].toInt()
            val glyphWidth = stats.get(largest, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val glyphHeight = stats.get(largest, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            val area = stats.get(largest, Imgproc.CC_STAT_AREA)[0]
            // Pencil marks, grid fragments and multiple glyphs are not single clues.
            if (glyphHeight < height * 0.38 || area < 24 || glyphWidth.toDouble() / glyphHeight > 1.15 ||
                area < Core.countNonZero(ink) * 0.90) return null
            Core.inRange(labels, org.opencv.core.Scalar(largest.toDouble()),
                org.opencv.core.Scalar(largest.toDouble()), isolated)
            component = isolated.submat(Rect(left, top, glyphWidth, glyphHeight))
            val scale = minOf(28.0 / glyphWidth, 44.0 / glyphHeight)
            val targetWidth = (glyphWidth * scale).roundToInt().coerceIn(1, WIDTH)
            val targetHeight = (glyphHeight * scale).roundToInt().coerceIn(1, HEIGHT)
            Imgproc.resize(component, normalized, Size(targetWidth.toDouble(), targetHeight.toDouble()),
                0.0, 0.0, Imgproc.INTER_AREA)
            check(normalized.type() == CvType.CV_8UC1)
            val bytes = ByteArray(targetWidth * targetHeight)
            normalized.get(0, 0, bytes)
            val result = FloatArray(WIDTH * HEIGHT)
            val offsetX = (WIDTH - targetWidth) / 2
            val offsetY = (HEIGHT - targetHeight) / 2
            for (y in 0 until targetHeight) for (x in 0 until targetWidth) {
                result[(offsetY + y) * WIDTH + offsetX + x] =
                    (bytes[y * targetWidth + x].toInt() and 0xff) / 255f
            }
            return result
        } finally {
            component?.release()
            interior?.release()
            listOf(rgba, gray, ink, labels, stats, centers, isolated, normalized).forEach { it.release() }
        }
    }

    private fun distance(a: FloatArray, b: FloatArray): Float {
        var difference = 0f
        var union = 0f
        for (index in a.indices) {
            difference += abs(a[index] - b[index])
            union += maxOf(a[index], b[index])
        }
        return if (union == 0f) 1f else difference / union
    }

    companion object {
        private const val TAG = "ScreenDigitMatcher"
        private const val WIDTH = 32
        private const val HEIGHT = 48
        private const val MAX_DISTANCE = 0.20f
        private const val MIN_MARGIN = 0.14f
    }
}
