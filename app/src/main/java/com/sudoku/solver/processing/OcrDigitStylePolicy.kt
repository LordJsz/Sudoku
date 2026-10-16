package com.sudoku.solver.processing

/**
 * Classifies the colour of a digit independently from the cell background.
 *
 * The target Sudoku app highlights selected cells (and every matching number) in blue.
 * Counting all blue-tinted pixels therefore mistakes a black given on a highlighted cell
 * for a blue player entry. This policy first estimates the local background with channel
 * medians, then considers only pixels that contrast with that background as digit ink.
 */
internal object OcrDigitStylePolicy {
    private const val MIN_INK_PIXELS = 24
    private const val MIN_BACKGROUND_CONTRAST = 30
    private const val BLUE_INK_RATIO_THRESHOLD = 0.55
    private const val RED_INK_RATIO_THRESHOLD = 0.50

    fun classifyArgbPixels(pixels: IntArray): OcrDigitStyle {
        if (pixels.isEmpty()) return OcrDigitStyle.NONE

        val redHistogram = IntArray(256)
        val greenHistogram = IntArray(256)
        val blueHistogram = IntArray(256)
        for (pixel in pixels) {
            redHistogram[(pixel ushr 16) and 0xff]++
            greenHistogram[(pixel ushr 8) and 0xff]++
            blueHistogram[pixel and 0xff]++
        }

        val backgroundRed = histogramMedian(redHistogram, pixels.size)
        val backgroundGreen = histogramMedian(greenHistogram, pixels.size)
        val backgroundBlue = histogramMedian(blueHistogram, pixels.size)
        val backgroundLuma = luma(backgroundRed, backgroundGreen, backgroundBlue)

        var inkPixels = 0
        var blueInkPixels = 0
        var redInkPixels = 0
        for (pixel in pixels) {
            val red = (pixel ushr 16) and 0xff
            val green = (pixel ushr 8) and 0xff
            val blue = pixel and 0xff
            val contrast = kotlin.math.abs(backgroundLuma - luma(red, green, blue))
            if (contrast < MIN_BACKGROUND_CONTRAST) continue

            inkPixels++
            val saturation = maxOf(red, green, blue) - minOf(red, green, blue)
            if (blue > red + 16 && blue > green + 8 && saturation > 20) {
                blueInkPixels++
            }
            if (red > green + 18 && red > blue + 12 && saturation > 22) {
                redInkPixels++
            }
        }

        if (inkPixels < MIN_INK_PIXELS) return OcrDigitStyle.NONE

        val blueRatio = blueInkPixels.toDouble() / inkPixels.toDouble()
        val redRatio = redInkPixels.toDouble() / inkPixels.toDouble()
        val blueStyled = blueRatio >= BLUE_INK_RATIO_THRESHOLD
        val redStyled = redRatio >= RED_INK_RATIO_THRESHOLD
        return when {
            blueStyled && !redStyled -> OcrDigitStyle.BLUE
            redStyled && !blueStyled -> OcrDigitStyle.RED
            blueStyled && redStyled -> {
                if (blueRatio >= redRatio) OcrDigitStyle.BLUE else OcrDigitStyle.RED
            }
            else -> OcrDigitStyle.NONE
        }
    }

    private fun histogramMedian(histogram: IntArray, sampleCount: Int): Int {
        val midpoint = (sampleCount + 1) / 2
        var cumulative = 0
        for (value in histogram.indices) {
            cumulative += histogram[value]
            if (cumulative >= midpoint) return value
        }
        return 0
    }

    private fun luma(red: Int, green: Int, blue: Int): Int {
        return (54 * red + 183 * green + 19 * blue) / 256
    }
}

internal enum class OcrDigitStyle {
    NONE,
    BLUE,
    RED
}
